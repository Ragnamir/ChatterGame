import { test } from 'node:test';
import assert from 'node:assert/strict';
import type {
  Channel,
  Character,
  FeedItem,
  GraphEdge,
  GraphNode,
} from '../src/api/types.ts';
import {
  buildChatRail,
  defaultSpeakerId,
  mergeFeed,
  nextAfterSeq,
  resolveSpeaker,
  speakersFor,
  unreadMap,
  visibleTransitions,
} from '../src/lib/room.ts';
import { renderWindow, isNearBottom } from '../src/lib/virtual.ts';
import { clearFeed, loadFeed, saveFeed, type StorageLike } from '../src/lib/feedCache.ts';
import { ReconnectClock } from '../src/lib/reconnect.ts';

function character(overrides: Partial<Character> = {}): Character {
  return {
    id: 'c1',
    gameId: 'g',
    name: 'Персонаж',
    portraitAssetId: null,
    appearance: null,
    publicHistory: null,
    privateHistory: null,
    playable: true,
    npc: false,
    archived: false,
    archivedReason: null,
    hidden: false,
    mine: true,
    ...overrides,
  };
}

function node(overrides: Partial<GraphNode> = {}): GraphNode {
  return {
    id: 'loc1',
    title: 'Зал',
    description: null,
    archived: false,
    archivedReason: null,
    present: [
      { id: 'c1', name: 'Ада', portraitAssetId: null, hidden: false },
      { id: 'c2', name: 'Борис', portraitAssetId: null, hidden: false },
    ],
    ...overrides,
  };
}

function channel(overrides: Partial<Channel> = {}): Channel {
  return {
    id: 'ch1',
    gameId: 'g',
    kind: 'WHISPER',
    title: 'Шёпот',
    disabled: false,
    archived: false,
    archivedReason: null,
    archivedAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    mySubjectKind: 'CHARACTER',
    myState: 'JOINED',
    master: false,
    unread: 0,
    members: [
      { subjectKind: 'CHARACTER', subjectId: 'c1', state: 'JOINED' },
      { subjectKind: 'CHARACTER', subjectId: 'c2', state: 'INVITED' },
    ],
    ...overrides,
  };
}

function message(seq: number, overrides: Partial<FeedItem> = {}): FeedItem {
  return {
    seq,
    kind: 'SPEECH',
    messageId: `m-${seq}`,
    chatId: 'loc1',
    characterId: 'c1',
    authorMembershipId: null,
    authorUserId: null,
    onBehalfOfMaster: false,
    hiddenAuthor: false,
    body: 'Реплика',
    systemCode: null,
    payload: null,
    createdAt: '2026-01-01T00:00:00Z',
    matchedNoteIds: [],
    ...overrides,
  };
}

class MemoryStorage implements StorageLike {
  private readonly map = new Map<string, string>();
  get length(): number {
    return this.map.size;
  }
  key(index: number): string | null {
    return [...this.map.keys()][index] ?? null;
  }
  getItem(key: string): string | null {
    return this.map.get(key) ?? null;
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }
  removeItem(key: string): void {
    this.map.delete(key);
  }
}

test('F02: 10 000 messages render a bounded window, not 10 000 nodes', () => {
  const windowed = renderWindow(0, 700, 84, 10_000);
  const rendered = windowed.end - windowed.start;
  assert.ok(rendered <= 30, `ожидалось ≤ 30 узлов, получено ${rendered}`);
  assert.equal(windowed.totalHeight, 840_000);
  assert.equal(windowed.offsetTop, 0);

  const middle = renderWindow(84 * 5000, 700, 84, 10_000);
  const middleRendered = middle.end - middle.start;
  assert.ok(middleRendered <= 30);
  assert.ok(middle.start > 4000);
});

test('F02: window position is bounded at the end of the list', () => {
  const atEnd = renderWindow(84 * 10_000, 700, 84, 10_000);
  assert.equal(atEnd.end, 10_000);
  assert.ok(atEnd.start < 10_000);
  assert.equal(renderWindow(0, 700, 84, 0).start, 0);
  assert.equal(renderWindow(0, 700, 84, 0).end, 0);
});

test('F02: window computation for 10 000 rows stays inside the frame budget', () => {
  const started = performance.now();
  for (let index = 0; index < 10_000; index += 1) {
    renderWindow(index * 84, 700, 84, 10_000);
  }
  const elapsed = performance.now() - started;
  assert.ok(elapsed < 100, `10 000 расчётов окна заняли ${elapsed.toFixed(1)} мс`);
});

test('F02: autoscroll only when the reader is already at the bottom', () => {
  assert.equal(isNearBottom(0, 700, 840_000), false);
  assert.equal(isNearBottom(839_300, 700, 840_000), true);
});

test('F02: speaker is mandatory with two present and implicit with one', () => {
  const two = [
    { id: 'c1', name: 'Ада', portraitAssetId: null, hidden: false },
    { id: 'c2', name: 'Борис', portraitAssetId: null, hidden: false },
  ];
  assert.equal(defaultSpeakerId(two), null);
  assert.equal(resolveSpeaker(two, null), null);
  assert.equal(resolveSpeaker(two, 'c2'), 'c2');
  assert.equal(resolveSpeaker(two, 'stranger'), null);

  const one = [two[0]!];
  assert.equal(defaultSpeakerId(one), 'c1');
  assert.equal(resolveSpeaker(one, null), 'c1');

  assert.equal(defaultSpeakerId([]), null);
  assert.equal(resolveSpeaker([], 'c1'), null);
});

test('F02: closed whisper excludes invited members from speakers', () => {
  const speakers = speakersFor('ch1', [node()], [channel()], [
    character({ id: 'c1' }),
    character({ id: 'c2' }),
  ]);
  assert.deepEqual(speakers.map((entry) => entry.id), ['c1']);
});

test('F02: hidden characters never become speakers', () => {
  const speakers = speakersFor(
    'loc1',
    [node({ present: [
      { id: 'c1', name: 'Ада', portraitAssetId: null, hidden: true },
      { id: 'c2', name: 'Борис', portraitAssetId: null, hidden: false },
    ] })],
    [],
    [character({ id: 'c1' }), character({ id: 'c2' })],
  );
  assert.deepEqual(speakers.map((entry) => entry.id), ['c2']);
});

test('F02: chat rail carries unread badges and keeps archived chats readable', () => {
  const channels = [
    channel({ id: 'w1', unread: 3 }),
    channel({ id: 'r1', kind: 'RADIO', disabled: true, archived: true, archivedReason: 'связь' }),
  ];
  const rail = buildChatRail(
    [node({ id: 'loc1' })],
    channels,
    unreadMap([{ chatId: 'loc1', count: 2 }, { chatId: 'w1', count: 5 }]),
  );
  const unread = new Map(rail.map((item) => [item.id, item.unread]));
  assert.equal(unread.get('loc1'), 2);
  assert.equal(unread.get('w1'), 5);
  assert.equal(unread.get('r1'), 0);

  const radio = rail.find((item) => item.id === 'r1');
  assert.equal(radio?.archived, true);
  assert.equal(radio?.disabled, true);
  assert.equal(radio?.reason, 'связь');
});

test('F02: unknown transitions stay off the map, closed carry the reason', () => {
  const edges: GraphEdge[] = [
    {
      id: 'e1',
      fromChatId: 'loc1',
      toChatId: 'loc2',
      title: null,
      archived: false,
      archivedReason: null,
      state: 'AVAILABLE',
      reason: null,
    },
    {
      id: 'e2',
      fromChatId: 'loc1',
      toChatId: 'loc3',
      title: 'Дверь',
      archived: false,
      archivedReason: null,
      state: 'KNOWN_UNAVAILABLE',
      reason: 'заперто',
    },
    {
      id: 'e3',
      fromChatId: 'loc1',
      toChatId: 'loc4',
      title: 'Тайник',
      archived: false,
      archivedReason: null,
      state: 'UNKNOWN',
      reason: null,
    },
  ];
  const visible = visibleTransitions(edges);
  assert.deepEqual(visible.map((edge) => edge.id), ['e1', 'e2']);
  assert.equal(visible[1]?.state, 'CLOSED');
  assert.equal(visible[1]?.reason, 'заперто');
});

test('F02: feed merge deduplicates by seq and keeps journal order', () => {
  const merged = mergeFeed([message(1), message(3)], [message(2), message(3, { body: 'новое' })]);
  assert.deepEqual(merged.map((item) => item.seq), [1, 2, 3]);
  assert.equal(merged[2]?.body, 'новое');
  assert.equal(nextAfterSeq(merged, 0), 3);
  assert.equal(nextAfterSeq([], 7), 7);
});

test('F02: offline cache is isolated by user, chat and subject', () => {
  const storage = new MemoryStorage();
  saveFeed(storage, 'u1', 'g1', 'loc1', 'c1', [message(1), message(2)], 2);

  assert.equal(loadFeed(storage, 'u1', 'g1', 'loc1', 'c1')?.items.length, 2);
  assert.equal(loadFeed(storage, 'u2', 'g1', 'loc1', 'c1'), null);
  assert.equal(loadFeed(storage, 'u1', 'g1', 'loc1', 'c2'), null);
  assert.equal(loadFeed(storage, 'u1', 'g1', 'loc2', 'c1'), null);

  clearFeed(storage, 'u1', 'g1');
  assert.equal(loadFeed(storage, 'u1', 'g1', 'loc1', 'c1'), null);
});

test('F02: cached feed keeps only the tail and survives broken JSON', () => {
  const storage = new MemoryStorage();
  const many = Array.from({ length: 500 }, (_, index) => message(index + 1));
  saveFeed(storage, 'u1', 'g1', 'loc1', null, many, 500);
  const cached = loadFeed(storage, 'u1', 'g1', 'loc1', null);
  assert.equal(cached?.items.length, 400);
  assert.equal(cached?.items[0]?.seq, 101);

  storage.setItem('chattergame.feed.v1:u1:g1:loc1:self', '{oops');
  assert.equal(loadFeed(storage, 'u1', 'g1', 'loc1', null), null);
});

test('F02: reconnect metric starts on drop and stops at first message', () => {
  const clock = new ReconnectClock();
  assert.equal(clock.markFirstMessage(1000), null);
  clock.markDisconnected(2000);
  assert.equal(clock.pending, true);
  const reading = clock.markFirstMessage(3500);
  assert.equal(reading?.elapsedMs, 1500);
  assert.equal(clock.pending, false);
  assert.equal(clock.last?.elapsedMs, 1500);
});
