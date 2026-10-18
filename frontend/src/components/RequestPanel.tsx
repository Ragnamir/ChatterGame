import { useState } from 'react';
import type { GameRequest, RequestOperation } from '../api/types';
import { translate, type MessageKey } from '../i18n/ru';
import { awaitingDecision, diffOf, llmError, pendingOrdinals, rollbackOrdinals } from '../lib/requests';

export interface RequestPanelProps {
  requests: readonly GameRequest[];
  onDecide: (requestId: string, approve: number[]) => Promise<void>;
  onRollback: (requestId: string, approve: number[]) => Promise<void>;
}

function statusKey(status: GameRequest['status']): MessageKey {
  return `request.status.${status}` as MessageKey;
}

function originKey(origin: GameRequest['origin']): MessageKey {
  return `request.origin.${origin}` as MessageKey;
}

function typeKey(type: GameRequest['type']): MessageKey {
  return `request.type.${type}` as MessageKey;
}

function operationStatusKey(status: RequestOperation['status']): MessageKey {
  return `request.operationStatus.${status}` as MessageKey;
}

function value(text: string | null): string {
  return text === null || text === '' ? translate('request.emptyValue') : text;
}

/**
 * Очередь мастера (`PLAN F05`, `D-041`): дифф по операциям и решение по каждой.
 *
 * <p>Выбор начинается с «все ждущие отмечены»: мастер чаще одобряет предложение целиком,
 * а снять галочку дешевле, чем поставить её на каждую строку. Отклонение — отдельная
 * кнопка, потому что пустой список одобренных и «ничего не отмечено» не должны совпадать
 * по смыслу (`D-135`).
 */
export function RequestPanel({ requests, onDecide, onRollback }: RequestPanelProps) {
  const [selected, setSelected] = useState<Record<string, number[]>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const open = requests.filter(
    (request) => awaitingDecision(request) || request.status === 'RUNNING' || request.status === 'FAILED',
  );

  function chosen(request: GameRequest): number[] {
    return selected[request.id] ?? pendingOrdinals(request);
  }

  function toggle(request: GameRequest, ordinal: number): void {
    const current = chosen(request);
    const next = current.includes(ordinal)
      ? current.filter((entry) => entry !== ordinal)
      : [...current, ordinal];
    setSelected((all) => ({ ...all, [request.id]: next }));
  }

  async function run(request: GameRequest, action: () => Promise<void>): Promise<void> {
    setBusy(request.id);
    setError(null);
    try {
      await action();
      setSelected((all) => {
        const next = { ...all };
        delete next[request.id];
        return next;
      });
    } catch {
      setError(translate('request.failed'));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel master" aria-labelledby="request-title">
      <h2 id="request-title">{translate('request.title')}</h2>
      <p className="panel__hint">{translate('request.hint')}</p>
      {error ? <p className="field__error">{error}</p> : null}
      {open.length === 0 ? <p className="panel__hint">{translate('request.empty')}</p> : null}

      <ul className="requests">
        {open.map((request) => {
          const failure = llmError(request);
          const waiting = request.status === 'RUNNING';
          const canDecide = awaitingDecision(request) && request.operations.length > 0;
          const rollbackable = rollbackOrdinals(request);
          return (
            <li key={request.id} className="requests__item">
              <div className="requests__head">
                <strong>
                  {translate(typeKey(request.type))} · {translate(originKey(request.origin))}
                </strong>
                <span className="requests__status">{translate(statusKey(request.status))}</span>
              </div>
              {waiting ? <p className="panel__hint">{translate('request.awaitingModel')}</p> : null}
              {failure ? (
                <p className="field__error">
                  {translate(failure.key)}
                  {failure.detail ? ` — ${failure.detail}` : ''}
                </p>
              ) : null}

              <ul className="requests__operations">
                {request.operations.map((operation) => (
                  <li key={operation.id} className="requests__operation">
                    <label className="requests__choose">
                      {operation.status === 'PENDING' ? (
                        <input
                          type="checkbox"
                          aria-label={translate('request.operation', { ordinal: operation.ordinal })}
                          checked={chosen(request).includes(operation.ordinal)}
                          onChange={() => toggle(request, operation.ordinal)}
                        />
                      ) : null}
                      {translate('request.operation', { ordinal: operation.ordinal })} ·{' '}
                      {translate(operationStatusKey(operation.status))}
                      {operation.reversible ? null : (
                        <span className="requests__irreversible">
                          {' '}
                          — {translate('request.irreversible')}
                        </span>
                      )}
                    </label>
                    <dl className="requests__diff">
                      {diffOf(operation).map((line) => (
                        <div key={line.labelKey} className="requests__line">
                          <dt>{translate(line.labelKey)}</dt>
                          {line.before === null ? null : (
                            <dd>
                              <span className="requests__label">{translate('request.before')}:</span>{' '}
                              {value(line.before)}
                            </dd>
                          )}
                          <dd>
                            <span className="requests__label">{translate('request.after')}:</span>{' '}
                            {value(line.after)}
                          </dd>
                        </div>
                      ))}
                    </dl>
                  </li>
                ))}
              </ul>

              <div className="form__actions">
                {canDecide ? (
                  <>
                    <button
                      className="button button--primary"
                      type="button"
                      disabled={busy === request.id}
                      onClick={() => void run(request, () => onDecide(request.id, chosen(request)))}
                    >
                      {translate('request.approveSelected')}
                    </button>
                    <button
                      className="button button--ghost"
                      type="button"
                      disabled={busy === request.id}
                      onClick={() => void run(request, () => onDecide(request.id, []))}
                    >
                      {translate('request.rejectAll')}
                    </button>
                  </>
                ) : null}
                {rollbackable.length > 0 ? (
                  <button
                    className="button button--ghost"
                    type="button"
                    disabled={busy === request.id}
                    onClick={() => void run(request, () => onRollback(request.id, rollbackable))}
                  >
                    {translate('request.rollbackSelected')}
                  </button>
                ) : null}
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
