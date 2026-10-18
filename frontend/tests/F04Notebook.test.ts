import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { FeedItem, Note, SavedMessage } from '../src/api/types.ts';
import { translate } from '../src/i18n/ru.ts';
import {
  canWriteNotes,
  isNoted,
  noteOwnerLabel,
  noteTitle,
  noteTitles,
  notebookScope,
  notebookTabs,
  notesForMessage,
  parseKeywords,
  pendingNoteIds,
  sortSaved,
} from '../src/lib/notebook.ts';

function note(overrides: Partial<Note> = {}): Note {
  return {
    id: 'n1',
    gameId: 'g',
    authorMembershipId: 'm1',
    targetKind: 'CHARACTER',
    targetId: 'c1',
    targetLabel: 'Иван',
    body: 'Иван — брат короля',
    matchingPending: false,
    createdAt: '2026-10-18T10:00:00Z',
    updatedAt: '2026-10-18T10:00:00Z',
    keywords: [{ raw: 'Иван', exactMatch: true }],
    ...overrides,
  };
}

function item(overrides: Partial<FeedItem> = {}): FeedItem {
  return {
    seq: 1,
    kind: 'SPEECH',
    messageId: 'msg1',
    chatId: 'chat1',
    characterId: 'c1',
    authorMembershipId: 'm1',
    authorUserId: 'u1',
    onBehalfOfMaster: false,
    hiddenAuthor: false,
    body: 'Иван вошёл в зал',
    systemCode: null,
    payload: null,
    createdAt: '2026-10-18T10:05:00Z',
    matchedNoteIds: [],
    ...overrides,
  };
}

function saved(overrides: Partial<SavedMessage> = {}): SavedMessage {
  return {
    id: 's1',
    gameId: 'g',
    ownerMembershipId: 'm1',
    messageId: 'msg1',
    textSnapshot: 'Иван вошёл в зал',
    speakerSnapshot: 'Иван',
    comment: null,
    noteId: null,
    createdAt: '2026-10-18T10:06:00Z',
    ...overrides,
  };
}

test('F04: the observer sees saved messages but has no notes tab', () => {
  assert.deepEqual(notebookTabs('OBSERVER'), ['saved']);
  assert.deepEqual(notebookTabs('PLAYER'), ['notes', 'saved']);
  assert.deepEqual(notebookTabs('MASTER'), ['notes', 'saved']);
  assert.equal(canWriteNotes('OBSERVER'), false);
  assert.equal(canWriteNotes('PLAYER'), true);
});

test('F04: the notebook is loaded per game and membership, not per character', () => {
  const scope = notebookScope('g1', 'm1');

  assert.equal(notebookScope('g1', 'm1'), scope);
  assert.notEqual(notebookScope('g2', 'm1'), scope);
  assert.notEqual(notebookScope('g1', 'm2'), scope);
  assert.equal(scope.includes('c1'), false);
});

test('F04: the owner marker is the note target, and the live name wins over the snapshot', () => {
  const names = new Map([['c1', 'Иван Грозный']]);
  assert.equal(noteOwnerLabel(note(), names), 'Иван Грозный');
  assert.equal(noteOwnerLabel(note({ targetKind: 'MEMBERSHIP', targetLabel: 'мастер' }), names), 'мастер');
  assert.equal(noteTitle(note(), names), 'Иван Грозный: Иван');
});

test('F04: highlighting reads matched_note_ids only and never searches the body', () => {
  const ids = new Set(['n1']);

  assert.equal(isNoted(item({ body: 'Иван вошёл в зал', matchedNoteIds: [] }), ids), false);
  assert.equal(isNoted(item({ body: 'кто-то вошёл', matchedNoteIds: ['n1'] }), ids), true);
  assert.equal(isNoted(item({ matchedNoteIds: ['unknown'] }), ids), false);
});

test('F04: a keyword conflict shows every matched note, not the first', () => {
  const first = note();
  const second = note({ id: 'n2', body: 'Иван скрывает имя', targetLabel: 'Иван' });
  const index = new Map([
    [first.id, first],
    [second.id, second],
  ]);

  assert.deepEqual(
    notesForMessage(index, ['n2', 'n1']).map((entry) => entry.id),
    ['n2', 'n1'],
  );
  assert.deepEqual(notesForMessage(index, ['n2', 'missing', 'n1']).map((entry) => entry.id), ['n2', 'n1']);
});

test('F04: keyword parsing keeps exact-match mode per keyword and drops duplicates', () => {
  assert.deepEqual(parseKeywords('Иван, =Иван, козлоногий , , иван'), [
    { raw: 'Иван', exactMatch: false },
    { raw: 'Иван', exactMatch: true },
    { raw: 'козлоногий', exactMatch: false },
  ]);
  assert.deepEqual(parseKeywords(''), []);
});

test('F04: a pending backfill is visible as a state, not as an empty notebook', () => {
  const pending = pendingNoteIds([note({ matchingPending: true }), note({ id: 'n2' })]);

  assert.deepEqual([...pending], ['n1']);
});

test('F04: note titles are ready for the feed without a second request', () => {
  const titles = noteTitles([note(), note({ id: 'n2', keywords: [] })], new Map([['c1', 'Иван']]));

  assert.equal(titles.get('n1'), 'Иван: Иван');
  assert.equal(titles.get('n2'), 'Иван');
});

test('F04: saved messages are ordered newest first and keep their snapshot', () => {
  const older = saved({ id: 's1', createdAt: '2026-10-18T10:00:00Z' });
  const newer = saved({ id: 's2', createdAt: '2026-10-18T11:00:00Z' });

  assert.deepEqual(sortSaved([older, newer]).map((entry) => entry.id), ['s2', 's1']);
  assert.equal(newer.textSnapshot, 'Иван вошёл в зал');
});

test('F04: notebook labels are translated, not hardcoded in components', () => {
  assert.equal(translate('notebook.tab.notes'), 'Заметки');
  assert.equal(translate('notebook.tab.saved'), 'Сохранённое');
  assert.equal(translate('notebook.pending').includes('Подсветка'), true);
});
