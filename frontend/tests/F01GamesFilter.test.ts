import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Game } from '../src/api/types.ts';
import {
  EMPTY_FILTERS,
  filterGames,
  hasActiveFilters,
  type GameFilters,
} from '../src/lib/gameFilters.ts';

function game(overrides: Partial<Game> = {}): Game {
  return {
    id: 'g',
    masterUserId: 'm',
    title: 'Игра',
    publicDescription: null,
    privateDescription: null,
    readiness: 'DRAFT',
    visibility: 'PRIVATE',
    phase: 'NOT_STARTED',
    paused: false,
    pauseReason: null,
    turnBased: false,
    archived: false,
    archivedReason: null,
    readOnly: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

const corpus: Game[] = [
  game({ id: 'a', readiness: 'DRAFT', visibility: 'PRIVATE', phase: 'NOT_STARTED' }),
  game({ id: 'b', readiness: 'READY', visibility: 'PUBLIC', phase: 'RUNNING' }),
  game({ id: 'c', readiness: 'READY', visibility: 'PUBLIC', phase: 'FINISHED' }),
  game({ id: 'd', readiness: 'READY', visibility: 'PRIVATE', phase: 'RUNNING' }),
];

function ids(filters: GameFilters): string[] {
  return filterGames(corpus, filters).map((entry) => entry.id);
}

test('F01: empty filters keep every game in server order', () => {
  assert.deepEqual(ids(EMPTY_FILTERS), ['a', 'b', 'c', 'd']);
  assert.equal(hasActiveFilters(EMPTY_FILTERS), false);
});

test('F01: each axis filters independently', () => {
  assert.deepEqual(ids({ ...EMPTY_FILTERS, readiness: 'READY' }), ['b', 'c', 'd']);
  assert.deepEqual(ids({ ...EMPTY_FILTERS, visibility: 'PRIVATE' }), ['a', 'd']);
  assert.deepEqual(ids({ ...EMPTY_FILTERS, phase: 'RUNNING' }), ['b', 'd']);
});

test('F01: axes combine with AND, not OR', () => {
  const filters: GameFilters = {
    readiness: 'READY',
    visibility: 'PUBLIC',
    phase: 'RUNNING',
  };
  assert.deepEqual(ids(filters), ['b']);
  assert.equal(hasActiveFilters(filters), true);
});

test('F01: a filter with no match returns an empty list, not everything', () => {
  const filters: GameFilters = {
    readiness: 'DRAFT',
    visibility: 'PUBLIC',
    phase: 'NOT_STARTED',
  };
  assert.deepEqual(ids(filters), []);
});

test('F01: filtering does not mutate the input list', () => {
  const copy = [...corpus];
  filterGames(corpus, { readiness: 'READY', visibility: 'ALL', phase: 'ALL' });
  assert.deepEqual(corpus, copy);
});

test('F01: archived and read-only games stay listed with their flags', () => {
  const archived = game({ id: 'e', archived: true, readOnly: true, phase: 'FINISHED' });
  const result = filterGames([archived], EMPTY_FILTERS);
  assert.equal(result.length, 1);
  assert.equal(result[0]?.archived, true);
  assert.equal(result[0]?.readOnly, true);
});
