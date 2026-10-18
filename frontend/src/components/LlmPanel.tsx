import { useState, type FormEvent } from 'react';
import type { Channel, Character, LlmStatus } from '../api/types';
import { translate } from '../i18n/ru';

export interface LlmPanelProps {
  status: LlmStatus | null;
  characters: readonly Character[];
  channels: readonly Channel[];
  onPropose: (input: {
    type: 'CO_MASTER' | 'AUTOPILOT';
    instruction: string;
    chatId: string | null;
    characterId: string | null;
  }) => Promise<void>;
}

type Call = 'CO_MASTER' | 'AUTOPILOT';

/**
 * Панель модели (`PLAN F05`, `D-042`, `D-092`): со-мастер и автопилот.
 *
 * <p>Без активного ключа панель не показывает форму вовсе: «кнопка, которая падает» хуже
 * скрытой опции, и решение `D-042` говорит именно о недоступности опций целиком.
 *
 * <p>Автопилот требует персонажа и сцену, потому что играет за конкретного персонажа в
 * конкретном чате; со-мастеру сцена нужна только чтобы сузить контекст.
 */
export function LlmPanel({ status, characters, channels, onPropose }: LlmPanelProps) {
  const [call, setCall] = useState<Call>('CO_MASTER');
  const [instruction, setInstruction] = useState('');
  const [characterId, setCharacterId] = useState('');
  const [chatId, setChatId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (status === null || !status.available) {
    return (
      <section className="panel master" aria-labelledby="llm-title">
        <h2 id="llm-title">{translate('llm.title')}</h2>
        <p className="panel__hint">{translate('llm.unavailable')}</p>
      </section>
    );
  }

  const active = characters.filter((character) => !character.archived);
  const scopes = channels.filter((channel) => !channel.archived);
  const needsTarget = call === 'AUTOPILOT';

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (instruction.trim().length === 0 || (needsTarget && (!characterId || !chatId))) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onPropose({
        type: call,
        instruction: instruction.trim(),
        chatId: chatId || null,
        characterId: characterId || null,
      });
      setInstruction('');
    } catch {
      setError(translate('llm.failed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel master" aria-labelledby="llm-title">
      <h2 id="llm-title">{translate('llm.title')}</h2>
      <p className="panel__hint">{translate('llm.hint')}</p>
      {error ? <p className="field__error">{error}</p> : null}
      <form className="master__group" onSubmit={(event) => void submit(event)}>
        <div className="field">
          <label htmlFor="llm-type">{translate('llm.type')}</label>
          <select
            id="llm-type"
            value={call}
            onChange={(event) => setCall(event.target.value as Call)}
          >
            <option value="CO_MASTER">{translate('llm.type.CO_MASTER')}</option>
            <option value="AUTOPILOT">{translate('llm.type.AUTOPILOT')}</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="llm-character">{translate('llm.character')}</label>
          <select
            id="llm-character"
            value={characterId}
            onChange={(event) => setCharacterId(event.target.value)}
          >
            <option value="">{translate('master.selectPlaceholder')}</option>
            {active.map((character) => (
              <option key={character.id} value={character.id}>
                {character.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="llm-chat">{translate('llm.chat')}</label>
          <select id="llm-chat" value={chatId} onChange={(event) => setChatId(event.target.value)}>
            <option value="">{translate('master.selectPlaceholder')}</option>
            {scopes.map((channel) => (
              <option key={channel.id} value={channel.id}>
                {channel.title}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="llm-instruction">{translate('llm.instruction')}</label>
          <textarea
            id="llm-instruction"
            value={instruction}
            maxLength={4000}
            rows={4}
            onChange={(event) => setInstruction(event.target.value)}
          />
        </div>
        <div className="form__actions">
          <button
            className="button button--primary"
            type="submit"
            disabled={busy || instruction.trim().length === 0 || (needsTarget && (!characterId || !chatId))}
          >
            {busy ? translate('llm.running') : translate('llm.submit')}
          </button>
        </div>
      </form>
    </section>
  );
}
