import { useState, type FormEvent } from 'react';
import type {
  Character,
  GraphNode,
  Membership,
  Restriction,
  RestrictionKind,
} from '../api/types';
import { translate } from '../i18n/ru';
import { formatRestrictionExpiry, RESTRICTION_LABEL } from '../lib/turn';

const KINDS: readonly RestrictionKind[] = ['NO_SPEECH', 'NO_ACTION', 'NO_MOVE', 'NO_JOKE'];

export interface MasterPanelProps {
  characters: readonly Character[];
  nodes: readonly GraphNode[];
  memberships: readonly Membership[];
  restrictions: readonly Restriction[];
  onMove: (characterId: string, chatId: string) => Promise<void>;
  onStealth: (characterId: string, hidden: boolean, reason: string | null) => Promise<void>;
  onRestrict: (input: {
    subjectKind: 'CHARACTER' | 'MEMBERSHIP';
    subjectId: string;
    kind: RestrictionKind;
    reason: string;
    expiresAt: string | null;
  }) => Promise<void>;
  onRevoke: (restrictionId: string) => Promise<void>;
}

/**
 * Мастер-панель (`PLAN F02`): персонажи, перемещение, скрытность, ограничения.
 * Отдельного «помощника мастера» нет (`D-021`), поэтому все действия видны здесь и
 * выполняются от имени мастера.
 */
export function MasterPanel({
  characters,
  nodes,
  memberships,
  restrictions,
  onMove,
  onStealth,
  onRestrict,
  onRevoke,
}: MasterPanelProps) {
  const [characterId, setCharacterId] = useState('');
  const [targetChatId, setTargetChatId] = useState('');
  const [reason, setReason] = useState('');
  const [kind, setKind] = useState<RestrictionKind>('NO_SPEECH');
  const [subjectId, setSubjectId] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const active = characters.filter((character) => !character.archived);
  const numericSubject = kind === 'NO_JOKE' ? memberships : active;

  async function run(action: () => Promise<void>): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch {
      setError(translate('master.failed'));
    } finally {
      setBusy(false);
    }
  }

  async function move(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (!characterId || !targetChatId) {
      return;
    }
    await run(() => onMove(characterId, targetChatId));
  }

  async function stealth(): Promise<void> {
    const character = active.find((entry) => entry.id === characterId);
    if (!character) {
      return;
    }
    await run(() => onStealth(character.id, !character.hidden, reason.trim() || null));
  }

  async function restrict(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (!subjectId || reason.trim().length === 0) {
      return;
    }
    await run(() =>
      onRestrict({
        subjectKind: kind === 'NO_JOKE' ? 'MEMBERSHIP' : 'CHARACTER',
        subjectId,
        kind,
        reason: reason.trim(),
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null,
      }),
    );
  }

  return (
    <section className="panel master" aria-labelledby="master-title">
      <h2 id="master-title">{translate('master.title')}</h2>
      <p className="panel__hint">{translate('master.hint')}</p>
      {error ? <p className="field__error">{error}</p> : null}

      <form className="master__group" onSubmit={(event) => void move(event)}>
        <h3>{translate('master.moveTitle')}</h3>
        <div className="field">
          <label htmlFor="master-character">{translate('master.character')}</label>
          <select
            id="master-character"
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
          <label htmlFor="master-location">{translate('master.location')}</label>
          <select
            id="master-location"
            value={targetChatId}
            onChange={(event) => setTargetChatId(event.target.value)}
          >
            <option value="">{translate('master.selectPlaceholder')}</option>
            {nodes.map((node) => (
              <option key={node.id} value={node.id}>
                {node.title}
              </option>
            ))}
          </select>
        </div>
        <div className="form__actions">
          <button className="button button--primary" type="submit" disabled={busy || !characterId || !targetChatId}>
            {translate('master.move')}
          </button>
          <button
            className="button button--ghost"
            type="button"
            disabled={busy || !characterId}
            onClick={() => void stealth()}
          >
            {translate('master.stealth')}
          </button>
        </div>
        <div className="field">
          <label htmlFor="master-stealth-reason">{translate('master.stealthReason')}</label>
          <input
            id="master-stealth-reason"
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
      </form>

      <form className="master__group" onSubmit={(event) => void restrict(event)}>
        <h3>{translate('master.restrictionsTitle')}</h3>
        <div className="field">
          <label htmlFor="master-restriction-kind">{translate('master.restrictionKind')}</label>
          <select
            id="master-restriction-kind"
            value={kind}
            onChange={(event) => {
              setKind(event.target.value as RestrictionKind);
              setSubjectId('');
            }}
          >
            {KINDS.map((value) => (
              <option key={value} value={value}>
                {translate(RESTRICTION_LABEL[value])}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="master-restriction-subject">{translate('master.restrictionSubject')}</label>
          <select
            id="master-restriction-subject"
            value={subjectId}
            onChange={(event) => setSubjectId(event.target.value)}
          >
            <option value="">{translate('master.selectPlaceholder')}</option>
            {numericSubject.map((subject) => (
              <option key={subject.id} value={subject.id}>
                {'name' in subject ? subject.name : translate('master.membership', { id: subject.id.slice(0, 8) })}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="master-restriction-reason">{translate('master.reason')}</label>
          <input
            id="master-restriction-reason"
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="master-restriction-until">{translate('master.restrictionUntil')}</label>
          <input
            id="master-restriction-until"
            type="datetime-local"
            value={expiresAt}
            onChange={(event) => setExpiresAt(event.target.value)}
          />
        </div>
        <div className="form__actions">
          <button className="button button--primary" type="submit" disabled={busy || !subjectId || reason.trim().length === 0}>
            {translate('master.restrict')}
          </button>
        </div>
      </form>

      <div className="master__group">
        <h3>{translate('master.activeRestrictions')}</h3>
        {restrictions.length === 0 ? (
          <p className="panel__hint">{translate('master.noRestrictions')}</p>
        ) : (
          <ul className="restrictions">
            {restrictions.map((restriction) => (
              <li key={restriction.id} className="restrictions__row">
                <span>{translate(RESTRICTION_LABEL[restriction.kind])}</span>
                <span className="restrictions__reason">{restriction.reason}</span>
                <span className="badge">{formatRestrictionExpiry(restriction.expiresAt)}</span>
                {restriction.revokedAt ? (
                  <span className="badge">{translate('master.revoked')}</span>
                ) : (
                  <button
                    type="button"
                    className="button button--ghost"
                    disabled={busy}
                    onClick={() => void run(() => onRevoke(restriction.id))}
                  >
                    {translate('master.revoke')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="master__group">
        <h3>{translate('master.charactersTitle')}</h3>
        <ul className="master__characters">
          {characters.map((character) => (
            <li key={character.id}>
              <span>{character.name}</span>
              {character.hidden ? <span className="badge">{translate('master.hidden')}</span> : null}
              {character.archived ? <span className="badge">{translate('master.archived')}</span> : null}
              {!character.playable ? <span className="badge">{translate('master.npc')}</span> : null}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
