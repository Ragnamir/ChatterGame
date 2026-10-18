import { useState, type FormEvent } from 'react';
import type { Note, NotebookTab, SavedMessage } from '../api/types';
import { translate } from '../i18n/ru';
import {
  noteOwnerLabel,
  parseKeywords,
  pendingNoteIds,
  sortSaved,
} from '../lib/notebook';

export interface NoteTarget {
  id: string;
  label: string;
  kind: 'CHARACTER' | 'MEMBERSHIP';
}

export interface NotebookProps {
  tabs: readonly NotebookTab[];
  notes: readonly Note[];
  saved: readonly SavedMessage[];
  names: ReadonlyMap<string, string>;
  noteById: ReadonlyMap<string, Note>;
  targets: readonly NoteTarget[];
  canWriteNotes: boolean;
  onCreateNote: (input: {
    targetKind: 'CHARACTER' | 'MEMBERSHIP';
    targetId: string;
    body: string;
    keywords: { raw: string; exactMatch: boolean }[];
  }) => Promise<void>;
  onComment: (savedId: string, comment: string) => Promise<void>;
}

/**
 * Тетрадь: заметки и сохранённые сообщения (`REQUIREMENTS §15`, `D-055`). Вкладка
 * «Заметки» наблюдателю не показывается вовсе, а не пустой: пустая вкладка выглядит
 * как потеря заметок, тогда как заметок у наблюдателя нет по решению (`D-063`).
 */
export function Notebook({
  tabs,
  notes,
  saved,
  names,
  noteById,
  targets,
  canWriteNotes,
  onCreateNote,
  onComment,
}: NotebookProps) {
  const [tab, setTab] = useState<NotebookTab>(tabs[0] ?? 'saved');
  const [targetId, setTargetId] = useState(targets[0]?.id ?? '');
  const [body, setBody] = useState('');
  const [keywords, setKeywords] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [comments, setComments] = useState<Record<string, string>>({});

  const pending = pendingNoteIds(notes);
  const activeTab = tabs.includes(tab) ? tab : (tabs[0] ?? 'saved');

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    const target = targets.find((entry) => entry.id === targetId);
    if (!target || body.trim().length === 0) {
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      await onCreateNote({
        targetKind: target.kind,
        targetId: target.id,
        body: body.trim(),
        keywords: parseKeywords(keywords),
      });
      setBody('');
      setKeywords('');
    } catch {
      setFailure(translate('notebook.failed'));
    } finally {
      setBusy(false);
    }
  }

  async function saveComment(savedId: string): Promise<void> {
    setFailure(null);
    try {
      await onComment(savedId, comments[savedId] ?? '');
    } catch {
      setFailure(translate('notebook.failed'));
    }
  }

  return (
    <section className="panel notebook" aria-labelledby="notebook-title">
      <h2 id="notebook-title">{translate('notebook.title')}</h2>
      <div className="notebook__tabs" role="tablist" aria-label={translate('notebook.title')}>
        {tabs.map((value) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={activeTab === value}
            className={`notebook__tab${activeTab === value ? ' notebook__tab--on' : ''}`}
            onClick={() => setTab(value)}
          >
            {translate(`notebook.tab.${value}`)}
          </button>
        ))}
      </div>

      {failure ? <p className="field__error">{failure}</p> : null}

      {activeTab === 'notes' ? (
        <>
          {canWriteNotes && targets.length > 0 ? (
            <form className="notebook__form" onSubmit={(event) => void submit(event)}>
              <div className="field">
                <label htmlFor="note-target">{translate('notebook.target')}</label>
                <select
                  id="note-target"
                  value={targetId}
                  onChange={(event) => setTargetId(event.target.value)}
                >
                  {targets.map((target) => (
                    <option key={`${target.kind}:${target.id}`} value={target.id}>
                      {target.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="note-body">{translate('notebook.body')}</label>
                <textarea
                  id="note-body"
                  rows={3}
                  maxLength={4000}
                  value={body}
                  onChange={(event) => setBody(event.target.value)}
                />
              </div>
              <div className="field">
                <label htmlFor="note-keywords">{translate('notebook.keywords')}</label>
                <input
                  id="note-keywords"
                  value={keywords}
                  onChange={(event) => setKeywords(event.target.value)}
                />
                <p className="field__hint">{translate('notebook.keywordsHint')}</p>
              </div>
              <div className="form__actions">
                <button
                  className="button button--primary"
                  type="submit"
                  disabled={busy || body.trim().length === 0}
                >
                  {translate('notebook.create')}
                </button>
              </div>
            </form>
          ) : null}

          {notes.length === 0 ? (
            <p className="panel__hint">{translate('notebook.notesEmpty')}</p>
          ) : (
            <ul className="notebook__list">
              {notes.map((note) => (
                <li key={note.id} className="notebook__note">
                  <p className="notebook__owner">
                    {translate('notebook.owner')}: {noteOwnerLabel(note, names)}
                  </p>
                  <p className="notebook__body">{note.body}</p>
                  {note.keywords.length > 0 ? (
                    <ul className="badges">
                      {note.keywords.map((keyword) => (
                        <li key={`${note.id}:${keyword.raw}:${keyword.exactMatch}`} className="badge">
                          {keyword.exactMatch ? `=${keyword.raw}` : keyword.raw}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  {pending.has(note.id) ? (
                    <p className="panel__hint">{translate('notebook.pending')}</p>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}

      {activeTab === 'saved' ? (
        saved.length === 0 ? (
          <p className="panel__hint">{translate('notebook.savedEmpty')}</p>
        ) : (
          <ul className="notebook__list">
            {sortSaved(saved).map((entry) => {
              const note = entry.noteId ? noteById.get(entry.noteId) : undefined;
              return (
                <li key={entry.id} className="notebook__saved">
                  <p className="notebook__owner">
                    {translate('notebook.savedBy', {
                      name: entry.speakerSnapshot ?? translate('feed.masterAuthor'),
                    })}
                  </p>
                  <blockquote className="notebook__body">{entry.textSnapshot}</blockquote>
                  {note ? (
                    <p className="panel__hint">
                      {translate('notebook.savedNote', { title: noteOwnerLabel(note, names) })}
                    </p>
                  ) : null}
                  <div className="field">
                    <label htmlFor={`saved-comment-${entry.id}`}>
                      {translate('notebook.savedComment')}
                    </label>
                    <input
                      id={`saved-comment-${entry.id}`}
                      aria-label={translate('notebook.savedComment')}
                      value={comments[entry.id] ?? entry.comment ?? ''}
                      onChange={(event) =>
                        setComments((current) => ({ ...current, [entry.id]: event.target.value }))
                      }
                    />
                  </div>
                  <button
                    type="button"
                    className="button button--ghost"
                    onClick={() => void saveComment(entry.id)}
                  >
                    {translate('common.save')}
                  </button>
                </li>
              );
            })}
          </ul>
        )
      ) : null}
    </section>
  );
}
