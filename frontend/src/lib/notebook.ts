import type { FeedItem, Membership, Note, NoteKeyword, NotebookTab, SavedMessage } from '../api/types';

/**
 * Тетрадь (`REQUIREMENTS §15`, `D-055`). Заметка принадлежит персонажу, сохранённое
 * сообщение — участию: поэтому у наблюдателя заметок нет вовсе (`D-063`), а сохранённое
 * есть. Это следствие решения, и клиент повторяет его как форму, а не фильтрует данные.
 */
export function notebookTabs(role: Membership['role'] | null): NotebookTab[] {
  return role === 'OBSERVER' ? ['saved'] : ['notes', 'saved'];
}

export function canWriteNotes(role: Membership['role'] | null): boolean {
  return role === 'MASTER' || role === 'PLAYER';
}

/**
 * Ключ загрузки тетради: игра и участие. Персонаж в него не входит намеренно — смена
 * говорящего не перезагружает тетрадь и не теряет вкладку (`PLAN F04`).
 */
export function notebookScope(gameId: string, membershipId: string | null): string {
  return `${gameId}:${membershipId ?? 'none'}`;
}

/**
 * Ключевые слова из одной строки. `=слово` — сравнение целиком (`D-051`): режим имени
 * принадлежит ключу, поэтому в одной заметке имя и обычное слово уживаются.
 */
export function parseKeywords(text: string): NoteKeyword[] {
  const seen = new Set<string>();
  const result: NoteKeyword[] = [];
  for (const chunk of text.split(',')) {
    const trimmed = chunk.trim();
    if (trimmed.length === 0) {
      continue;
    }
    const exactMatch = trimmed.startsWith('=');
    const raw = (exactMatch ? trimmed.slice(1) : trimmed).trim();
    const key = `${exactMatch ? '=' : ''}${raw.toLowerCase()}`;
    if (raw.length === 0 || seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push({ raw, exactMatch });
  }
  return result;
}

/**
 * Маркер владельца (`D-053`): подпись цели приходит готовой (`D-127`), имя персонажа —
 * живое, чтобы правка карточки не оставляла в тетради старое имя.
 */
export function noteOwnerLabel(note: Note, names: ReadonlyMap<string, string>): string {
  if (note.targetKind === 'CHARACTER') {
    return names.get(note.targetId) ?? note.targetLabel;
  }
  return note.targetLabel;
}

/** Заголовок заметки для ленты: чья она и по каким ключам сработала. */
export function noteTitle(note: Note, names: ReadonlyMap<string, string>): string {
  const owner = noteOwnerLabel(note, names);
  return note.keywords.length === 0 ? owner : `${owner}: ${note.keywords.map((keyword) => keyword.raw).join(', ')}`;
}

export function noteTitles(notes: readonly Note[], names: ReadonlyMap<string, string>): Map<string, string> {
  return new Map(notes.map((note) => [note.id, noteTitle(note, names)]));
}

/**
 * Подсветка строится только по `matchedNoteIds` с сервера (`D-050`, `D-052`). Клиент не
 * ищет ключи в тексте: иначе подсветка разошлась бы с материализованной видимостью и
 * зависела бы от регистра и словоформ на каждом клиенте по-своему.
 */
export function isNoted(item: FeedItem, noteIds: ReadonlySet<string>): boolean {
  return item.matchedNoteIds.some((id) => noteIds.has(id));
}

/**
 * Заметки, подсветившие сообщение, в порядке ответа сервера. При конфликте ключей
 * (`D-052`) возвращаются все, а не первая: иначе игрок не увидит, что сработало две.
 */
export function notesForMessage(
  index: ReadonlyMap<string, Note>,
  matchedIds: readonly string[],
): Note[] {
  const result: Note[] = [];
  for (const id of matchedIds) {
    const note = index.get(id);
    if (note) {
      result.push(note);
    }
  }
  return result;
}

/** Пока идёт досчёт (`D-087`), тетрадь говорит «подсветка готовится», а не молчит. */
export function pendingNoteIds(notes: readonly Note[]): Set<string> {
  return new Set(notes.filter((note) => note.matchingPending).map((note) => note.id));
}

/**
 * Сохранённые сообщения принадлежат участию и переживают смену персонажа. Сортировка —
 * по времени записи, свежие сверху: тетрадь читают как список закладок, а не как ленту.
 */
export function sortSaved(saved: readonly SavedMessage[]): SavedMessage[] {
  return [...saved].sort((left, right) => right.createdAt.localeCompare(left.createdAt));
}

/** Снимок сообщения не меняется задним числом (`D-055`), поэтому показываем его как есть. */
export function savedSpeaker(saved: SavedMessage): string | null {
  return saved.speakerSnapshot;
}
