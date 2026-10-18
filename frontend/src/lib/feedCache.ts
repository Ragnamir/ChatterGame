import type { FeedItem } from '../api/types';

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

const PREFIX = 'chattergame.feed.v1';
const LIMIT = 400;

export interface CachedFeed {
  items: FeedItem[];
  lastSeq: number;
  savedAt: string;
}

function keyOf(userId: string, gameId: string, chatId: string, subjectId: string | null): string {
  return `${PREFIX}:${userId}:${gameId}:${chatId}:${subjectId ?? 'self'}`;
}

/**
 * Кэш последней ленты для офлайн-чтения (`PLAN F02`). Ключ включает пользователя,
 * игру, чат и субъекта: иначе после смены персонажа офлайн показал бы чужую ленту.
 * Хранится только хвост: полная история в браузере не нужна и не поместится.
 */
export function saveFeed(
  storage: StorageLike,
  userId: string,
  gameId: string,
  chatId: string,
  subjectId: string | null,
  items: readonly FeedItem[],
  lastSeq: number,
): void {
  const tail = items.slice(-LIMIT);
  const payload: CachedFeed = { items: tail, lastSeq, savedAt: new Date().toISOString() };
  try {
    storage.setItem(keyOf(userId, gameId, chatId, subjectId), JSON.stringify(payload));
  } catch {
    // Переполнение хранилища не должно ломать ленту: кэш — удобство, а не источник истины.
  }
}

export function loadFeed(
  storage: StorageLike,
  userId: string,
  gameId: string,
  chatId: string,
  subjectId: string | null,
): CachedFeed | null {
  const raw = storage.getItem(keyOf(userId, gameId, chatId, subjectId));
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as Partial<CachedFeed>;
    if (!Array.isArray(parsed.items) || typeof parsed.lastSeq !== 'number') {
      return null;
    }
    return {
      items: parsed.items as FeedItem[],
      lastSeq: parsed.lastSeq,
      savedAt: typeof parsed.savedAt === 'string' ? parsed.savedAt : '',
    };
  } catch {
    return null;
  }
}

export function clearFeed(storage: StorageLike, userId: string, gameId: string): void {
  const prefix = `${PREFIX}:${userId}:${gameId}:`;
  const keys: string[] = [];
  for (let index = 0; index < storageLength(storage); index += 1) {
    const key = storageKey(storage, index);
    if (key?.startsWith(prefix)) {
      keys.push(key);
    }
  }
  for (const key of keys) {
    storage.removeItem(key);
  }
}

interface LengthAwareStorage extends StorageLike {
  readonly length?: number;
  key?(index: number): string | null;
}

function storageLength(storage: StorageLike): number {
  return (storage as LengthAwareStorage).length ?? 0;
}

function storageKey(storage: StorageLike, index: number): string | null {
  return (storage as LengthAwareStorage).key?.(index) ?? null;
}
