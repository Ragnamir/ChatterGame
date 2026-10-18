import { useEffect, useState, type FormEvent } from 'react';
import type { MessageKind, PresentCharacter } from '../api/types';
import { translate, type MessageKey } from '../i18n/ru';

const KIND_LABEL: Record<MessageKind, MessageKey> = {
  SPEECH: 'composer.kind.SPEECH',
  ACTION: 'composer.kind.ACTION',
  JOKE: 'composer.kind.JOKE',
  MASTER: 'composer.kind.MASTER',
  SYSTEM: 'composer.kind.SYSTEM',
};

export interface ComposerProps {
  speakers: readonly PresentCharacter[];
  selectedSpeakerId: string | null;
  onSelectSpeaker: (id: string | null) => void;
  onSend: (kind: MessageKind, body: string) => Promise<void>;
  disabledReason: string | null;
  blockedKinds: ReadonlyMap<MessageKind, string>;
  kinds: readonly MessageKind[];
  allowNoSpeaker: boolean;
}

/**
 * Палитра типов и поле реплики. Выбор персонажа обязателен при двух присутствующих и
 * скрыт при одном (`PLAN F02`); при нуле отправка закрыта с причиной, а не молча.
 * Мастеру доступен тип `MASTER` без персонажа — это событие мира (`D-004`).
 */
export function Composer({
  speakers,
  selectedSpeakerId,
  onSelectSpeaker,
  onSend,
  disabledReason,
  blockedKinds,
  kinds,
  allowNoSpeaker,
}: ComposerProps) {
  const [kind, setKind] = useState<MessageKind>(kinds[0] ?? 'SPEECH');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    if (!allowNoSpeaker) {
      return;
    }
    if (selectedSpeakerId === null && kind !== 'MASTER') {
      setKind('MASTER');
    } else if (selectedSpeakerId !== null && kind === 'MASTER') {
      setKind('SPEECH');
    }
  }, [allowNoSpeaker, selectedSpeakerId, kind]);

  const speakerMissing = !allowNoSpeaker && speakers.length !== 1 && selectedSpeakerId === null;
  const blocked = disabledReason !== null || speakerMissing || busy;
  const kindBlocked = blockedKinds.get(kind) ?? null;

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (blocked || kindBlocked !== null || body.trim().length === 0) {
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      await onSend(kind, body.trim());
      setBody('');
    } catch {
      setFailure(translate('composer.failed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="composer" onSubmit={(event) => void submit(event)}>
      {disabledReason ? (
        <p className="composer__blocked" role="status">
          {disabledReason}
        </p>
      ) : null}
      {speakerMissing && disabledReason === null ? (
        <p className="composer__blocked" role="status">
          {translate('composer.chooseSpeaker')}
        </p>
      ) : null}
      {failure ? <p className="field__error">{failure}</p> : null}
      {kindBlocked ? (
        <p className="composer__blocked" role="status">
          {kindBlocked}
        </p>
      ) : null}

      {allowNoSpeaker || speakers.length > 1 ? (
        <div className="field">
          <label htmlFor="composer-speaker">{translate('composer.speaker')}</label>
          <select
            id="composer-speaker"
            value={selectedSpeakerId ?? ''}
            onChange={(event) => onSelectSpeaker(event.target.value || null)}
          >
            <option value="">
              {allowNoSpeaker ? translate('composer.masterSpeaker') : translate('composer.speakerPlaceholder')}
            </option>
            {speakers.map((speaker) => (
              <option key={speaker.id} value={speaker.id}>
                {speaker.name}
              </option>
            ))}
          </select>
        </div>
      ) : null}

      <fieldset className="palette" disabled={blocked}>
        <legend className="sr-only">{translate('composer.kindLabel')}</legend>
        {kinds.map((value) => (
          <label key={value} className={`palette__choice${kind === value ? ' palette__choice--on' : ''}`}>
            <input
              type="radio"
              name="message-kind"
              value={value}
              checked={kind === value}
              disabled={blockedKinds.has(value)}
              aria-label={translate(KIND_LABEL[value])}
              onChange={() => setKind(value)}
            />
            {translate(KIND_LABEL[value])}
          </label>
        ))}
      </fieldset>

      <div className="field">
        <label htmlFor="composer-body">{translate('composer.body')}</label>
        <textarea
          id="composer-body"
          value={body}
          maxLength={4000}
          rows={3}
          disabled={blocked}
          onChange={(event) => setBody(event.target.value)}
        />
      </div>

      <div className="form__actions">
        <button className="button button--primary" type="submit" disabled={blocked || body.trim().length === 0}>
          {busy ? translate('composer.sending') : translate('composer.send')}
        </button>
        <span className="composer__hint">{translate('composer.hint')}</span>
      </div>
    </form>
  );
}
