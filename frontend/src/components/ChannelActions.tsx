import { useEffect, useState, type FormEvent } from 'react';
import type { Channel, Character } from '../api/types';
import { translate } from '../i18n/ru';

export interface ChannelActionsProps {
  isMaster: boolean;
  isObserver: boolean;
  activeChannel: Channel | null;
  /** Персонажи, с которыми можно заговорить здесь: присутствующие в открытой локации. */
  candidates: readonly Character[];
  characters: readonly Character[];
  ownCharacterId: string | null;
  onCreateWhisper: (characterIds: string[]) => Promise<void>;
  onWhisperDecision: (chatId: string, action: 'accept' | 'decline' | 'close') => Promise<void>;
  onCreateRadio: (title: string, characterIds: string[]) => Promise<void>;
  onRadioMembers: (chatId: string, characterIds: string[]) => Promise<void>;
  onRadioDisabled: (chatId: string, disabled: boolean) => Promise<void>;
}

/**
 * Личные каналы (`REQUIREMENTS §7.2`–§7.4`). Шёпот начинается от лица своего персонажа,
 * остальные входят по согласию (`D-038`); радиочат задаётся кругом персонажей и живёт
 * независимо от места (`D-039`). Состав канала приходит с сервера, клиент его не выводит.
 */
export function ChannelActions({
  isMaster,
  isObserver,
  activeChannel,
  candidates,
  characters,
  ownCharacterId,
  onCreateWhisper,
  onWhisperDecision,
  onCreateRadio,
  onRadioMembers,
  onRadioDisabled,
}: ChannelActionsProps) {
  const [whisperIds, setWhisperIds] = useState<string[]>([]);
  const [radioTitle, setRadioTitle] = useState('');
  const [newRadioIds, setNewRadioIds] = useState<string[]>([]);
  const [radioIds, setRadioIds] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    setWhisperIds(ownCharacterId ? [ownCharacterId] : []);
  }, [ownCharacterId]);

  useEffect(() => {
    if (activeChannel?.kind === 'RADIO') {
      setRadioIds(
        activeChannel.members
          .filter((member) => member.subjectKind === 'CHARACTER' && member.state === 'JOINED')
          .map((member) => member.subjectId),
      );
    }
  }, [activeChannel]);

  const liveCharacters = characters.filter((character) => !character.archived);

  function toggle(list: string[], id: string): string[] {
    return list.includes(id) ? list.filter((entry) => entry !== id) : [...list, id];
  }

  async function run(action: () => Promise<void>): Promise<void> {
    setBusy(true);
    setFailure(null);
    try {
      await action();
    } catch {
      setFailure(translate('channel.failed'));
    } finally {
      setBusy(false);
    }
  }

  async function submitWhisper(event: FormEvent): Promise<void> {
    event.preventDefault();
    await run(() => onCreateWhisper(whisperIds));
  }

  async function submitRadio(event: FormEvent): Promise<void> {
    event.preventDefault();
    await run(() => onCreateRadio(radioTitle.trim(), newRadioIds));
    setRadioTitle('');
  }

  if (isObserver) {
    return null;
  }

  const invitation = activeChannel?.kind === 'WHISPER' && activeChannel.myState === 'INVITED';
  const whisperOpen = activeChannel?.kind === 'WHISPER' && activeChannel.myState === 'JOINED';

  return (
    <section className="panel channels" aria-labelledby="channels-title">
      <h2 id="channels-title">{translate('channel.title')}</h2>
      {failure ? <p className="field__error">{failure}</p> : null}

      {invitation ? (
        <div className="channels__invitation" role="status">
          <p>{translate('channel.invited')}</p>
          <div className="form__actions">
            <button
              type="button"
              className="button button--primary"
              disabled={busy}
              onClick={() => void run(() => onWhisperDecision(activeChannel.id, 'accept'))}
            >
              {translate('channel.accept')}
            </button>
            <button
              type="button"
              className="button button--ghost"
              disabled={busy}
              onClick={() => void run(() => onWhisperDecision(activeChannel.id, 'decline'))}
            >
              {translate('channel.decline')}
            </button>
          </div>
        </div>
      ) : null}

      {whisperOpen ? (
        <button
          type="button"
          className="button button--ghost"
          disabled={busy}
          onClick={() => void run(() => onWhisperDecision(activeChannel.id, 'close'))}
        >
          {translate('channel.close')}
        </button>
      ) : null}

      <form className="channels__form" onSubmit={(event) => void submitWhisper(event)}>
        <h3>{translate('channel.whisperTitle')}</h3>
        <p className="panel__hint">{translate('channel.whisperHint')}</p>
        {candidates.length === 0 ? (
          <p className="panel__hint">{translate('channel.noCandidates')}</p>
        ) : (
          <fieldset className="channels__members">
            <legend>{translate('channel.members')}</legend>
            {candidates.map((character) => (
              <label key={character.id} className="channels__member">
                <input
                  type="checkbox"
                  aria-label={character.name}
                  checked={whisperIds.includes(character.id)}
                  onChange={() => setWhisperIds((current) => toggle(current, character.id))}
                />
                {character.name}
              </label>
            ))}
          </fieldset>
        )}
        <div className="form__actions">
          <button
            className="button button--primary"
            type="submit"
            disabled={busy || whisperIds.length === 0}
          >
            {translate('channel.createWhisper')}
          </button>
        </div>
      </form>

      {isMaster ? (
        <>
          <form className="channels__form" onSubmit={(event) => void submitRadio(event)}>
            <h3>{translate('channel.radioTitle')}</h3>
            <div className="field">
              <label htmlFor="radio-title">{translate('channel.radioName')}</label>
              <input
                id="radio-title"
                value={radioTitle}
                maxLength={200}
                onChange={(event) => setRadioTitle(event.target.value)}
              />
            </div>
            <fieldset className="channels__members">
              <legend>{translate('channel.members')}</legend>
              {liveCharacters.map((character) => (
                <label key={character.id} className="channels__member">
                  <input
                    type="checkbox"
                    aria-label={character.name}
                    checked={newRadioIds.includes(character.id)}
                    onChange={() => setNewRadioIds((current) => toggle(current, character.id))}
                  />
                  {character.name}
                </label>
              ))}
            </fieldset>
            <div className="form__actions">
              <button
                type="submit"
                className="button button--primary"
                disabled={busy || radioTitle.trim().length === 0}
              >
                {translate('channel.createRadio')}
              </button>
            </div>
          </form>

          {activeChannel?.kind === 'RADIO' ? (
            <div className="channels__form">
              <h3>{translate('channel.radioMembersTitle')}</h3>
              <fieldset className="channels__members">
                <legend>{translate('channel.members')}</legend>
                {liveCharacters.map((character) => (
                  <label key={character.id} className="channels__member">
                    <input
                      type="checkbox"
                      aria-label={character.name}
                      checked={radioIds.includes(character.id)}
                      onChange={() => setRadioIds((current) => toggle(current, character.id))}
                    />
                    {character.name}
                  </label>
                ))}
              </fieldset>
              <div className="form__actions">
                <button
                  type="button"
                  className="button button--ghost"
                  disabled={busy}
                  onClick={() => void run(() => onRadioMembers(activeChannel.id, radioIds))}
                >
                  {translate('channel.saveMembers')}
                </button>
                <button
                  type="button"
                  className="button button--ghost"
                  disabled={busy}
                  onClick={() =>
                    void run(() => onRadioDisabled(activeChannel.id, !activeChannel.disabled))
                  }
                >
                  {activeChannel.disabled
                    ? translate('channel.enable')
                    : translate('channel.disable')}
                </button>
              </div>
            </div>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
