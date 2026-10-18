import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Restriction, Turn } from '../src/api/types.ts';
import { translate } from '../src/i18n/ru.ts';
import {
  blockedKindReasons,
  formatRestrictionExpiry,
  restrictionsForViewer,
  type TurnContext,
} from '../src/lib/turn.ts';

const NOW = Date.parse('2026-10-18T12:00:00Z');

function turn(overrides: Partial<Turn> = {}): Turn {
  return {
    turnBased: true,
    open: true,
    n: 1,
    openedAt: '2026-10-18T11:00:00Z',
    closedAt: null,
    budgets: [],
    ...overrides,
  };
}

function restriction(overrides: Partial<Restriction> = {}): Restriction {
  return {
    id: 'r1',
    gameId: 'g',
    subjectKind: 'CHARACTER',
    subjectId: 'c1',
    kind: 'NO_SPEECH',
    reason: 'попал в плен',
    createdBy: 'master',
    createdAt: '2026-10-18T10:00:00Z',
    expiresAt: null,
    revokedAt: null,
    ...overrides,
  };
}

function context(overrides: Partial<TurnContext> = {}): TurnContext {
  return {
    phase: 'RUNNING',
    turn: turn(),
    restrictions: [],
    ...overrides,
  };
}

test('F03: closed turn blocks speech and action with the turn reason, joke stays open', () => {
  const blocked = blockedKindReasons(
    context({ turn: turn({ open: false }) }),
    'LOCATION',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.get('SPEECH'), translate('room.turnClosed'));
  assert.equal(blocked.get('ACTION'), translate('room.turnClosed'));
  assert.equal(blocked.has('JOKE'), false);
  assert.equal(blocked.has('MASTER'), false);
});

test('F03: closed turn keeps the whisper channel usable for a joke', () => {
  const blocked = blockedKindReasons(
    context({ turn: turn({ open: false }) }),
    'WHISPER',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.get('SPEECH'), translate('room.turnClosed'));
  assert.equal(blocked.has('JOKE'), false);
});

test('F03: an open turn leaves speech and action available', () => {
  const blocked = blockedKindReasons(context(), 'LOCATION', 'c1', 'm1', false, NOW);

  assert.equal(blocked.size, 0);
});

test('F03: turn state does not block a game without turn-based mode', () => {
  const blocked = blockedKindReasons(
    context({ turn: turn({ turnBased: false, open: false }) }),
    'LOCATION',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.size, 0);
});

test('F03: spent budget closes only the spent kind before the send attempt', () => {
  const blocked = blockedKindReasons(
    context({ turn: turn({ budgets: [{ characterId: 'c1', speechUsed: true, actionUsed: false }] }) }),
    'LOCATION',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.get('SPEECH'), translate('room.budgetSpeech'));
  assert.equal(blocked.has('ACTION'), false);
  assert.equal(blocked.has('JOKE'), false);
});

test('F03: a budget of another character does not close my kinds', () => {
  const blocked = blockedKindReasons(
    context({ turn: turn({ budgets: [{ characterId: 'c2', speechUsed: true, actionUsed: true }] }) }),
    'LOCATION',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.size, 0);
});

test('F03: a restriction reason reaches the player it addresses', () => {
  const blocked = blockedKindReasons(
    context({ restrictions: [restriction()] }),
    'LOCATION',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.get('SPEECH'), translate('room.restricted', { reason: 'попал в плен' }));
  assert.equal(blocked.has('ACTION'), false);
});

test('F03: a restriction addressed to another character does not block me', () => {
  const blocked = blockedKindReasons(
    context({ restrictions: [restriction({ subjectId: 'c2' })] }),
    'LOCATION',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.size, 0);
});

test('F03: a joke ban is addressed to the membership, not to the character', () => {
  const ban = restriction({ kind: 'NO_JOKE', subjectKind: 'MEMBERSHIP', subjectId: 'm1', reason: 'флуд' });

  const mine = blockedKindReasons(context({ restrictions: [ban] }), 'LOCATION', 'c1', 'm1', false, NOW);
  const foreign = blockedKindReasons(context({ restrictions: [ban] }), 'LOCATION', 'c1', 'm2', false, NOW);

  assert.equal(mine.get('JOKE'), translate('room.restricted', { reason: 'флуд' }));
  assert.equal(foreign.size, 0);
});

test('F03: revoked and expired restrictions stop blocking', () => {
  const restrictions = [
    restriction({ id: 'r1', revokedAt: '2026-10-18T11:30:00Z' }),
    restriction({ id: 'r2', kind: 'NO_ACTION', expiresAt: '2026-10-18T11:59:59Z' }),
  ];

  const blocked = blockedKindReasons(context({ restrictions }), 'LOCATION', 'c1', 'm1', false, NOW);

  assert.equal(blocked.size, 0);
});

test('F03: a live deadline keeps the restriction in force', () => {
  const blocked = blockedKindReasons(
    context({ restrictions: [restriction({ expiresAt: '2026-10-18T12:00:01Z' })] }),
    'LOCATION',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.get('SPEECH'), translate('room.restricted', { reason: 'попал в плен' }));
});

test('F03: the player sees only own active restrictions', () => {
  const restrictions = [
    restriction(),
    restriction({ id: 'r2', subjectId: 'c2' }),
    restriction({ id: 'r3', revokedAt: '2026-10-18T11:00:00Z' }),
    restriction({ id: 'r4', kind: 'NO_JOKE', subjectKind: 'MEMBERSHIP', subjectId: 'm1' }),
  ];

  const visible = restrictionsForViewer(restrictions, 'c1', 'm1', NOW);

  assert.deepEqual(visible.map((entry) => entry.id), ['r1', 'r4']);
});

test('F03: restriction deadline is shown as a date, without any turn timing', () => {
  assert.equal(formatRestrictionExpiry('2026-10-18T12:30:00Z', 'UTC'), 'до 18.10.2026, 12:30');
  assert.equal(formatRestrictionExpiry(null), translate('restriction.noDeadline'));
});

test('F03: the personal chat with the master is outside the turn count', () => {
  const closed = blockedKindReasons(
    context({
      turn: turn({ open: false, budgets: [{ characterId: 'c1', speechUsed: true, actionUsed: true }] }),
    }),
    'MASTER_DM',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(closed.size, 0);
});

test('F03: a master restriction still reaches the personal chat with the master', () => {
  const blocked = blockedKindReasons(
    context({ restrictions: [restriction()] }),
    'MASTER_DM',
    'c1',
    'm1',
    false,
    NOW,
  );

  assert.equal(blocked.get('SPEECH'), translate('room.restricted', { reason: 'попал в плен' }));
});

test('F03: radio keeps only speech and the final chat keeps only the master event', () => {
  const radio = blockedKindReasons(context(), 'RADIO', 'c1', 'm1', false, NOW);
  const final = blockedKindReasons(context(), 'FINAL', 'c1', 'm1', false, NOW);
  const finalMaster = blockedKindReasons(context(), 'FINAL', 'c1', 'm1', true, NOW);

  assert.equal(radio.get('ACTION'), translate('composer.radioSpeechOnly'));
  assert.equal(radio.get('JOKE'), translate('composer.radioSpeechOnly'));
  assert.equal(final.get('MASTER'), translate('composer.masterOnly'));
  assert.equal(finalMaster.has('MASTER'), false);
});
