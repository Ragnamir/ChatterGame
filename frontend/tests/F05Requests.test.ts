import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { GameRequest, RequestOperation } from '../src/api/types.ts';
import { translate } from '../src/i18n/ru.ts';
import {
  awaitingDecision,
  diffOf,
  llmError,
  pendingOrdinals,
  rollbackOrdinals,
} from '../src/lib/requests.ts';

function operation(overrides: Partial<RequestOperation> = {}): RequestOperation {
  return {
    id: 'op1',
    ordinal: 1,
    op: 'update_character_field',
    status: 'PENDING',
    reversible: true,
    payload: { characterId: 'c1', field: 'appearance', value: 'седой' },
    oldValue: null,
    ...overrides,
  };
}

function request(overrides: Partial<GameRequest> = {}): GameRequest {
  return {
    id: 'r1',
    gameId: 'g1',
    origin: 'LLM',
    type: 'CO_MASTER',
    fromMembershipId: 'm1',
    toCharacterId: null,
    payload: {},
    status: 'SUCCEEDED',
    error: null,
    idempotencyKey: null,
    createdAt: '2026-10-18T10:00:00Z',
    expiresAt: null,
    operations: [operation()],
    ...overrides,
  };
}

test('F05: решению подлежат только ждущие операции', () => {
  const value = request({
    operations: [
      operation({ ordinal: 1, status: 'PENDING' }),
      operation({ ordinal: 2, status: 'APPLIED' }),
      operation({ ordinal: 3, status: 'PENDING' }),
      operation({ ordinal: 4, status: 'REJECTED' }),
    ],
  });

  assert.deepEqual(pendingOrdinals(value), [1, 3]);
});

test('F05: откат предлагается только для применённых обратимых операций', () => {
  const value = request({
    operations: [
      operation({ ordinal: 1, status: 'APPLIED', reversible: true }),
      operation({ ordinal: 2, status: 'APPLIED', reversible: false }),
      operation({ ordinal: 3, status: 'PENDING', reversible: true }),
      operation({ ordinal: 4, status: 'ROLLED_BACK', reversible: true }),
    ],
  });

  assert.deepEqual(
    rollbackOrdinals(value),
    [1],
    'необратимая операция не должна попадать в откат: удаление запрещено (D-010)',
  );
});

test('F05: решение принимается для готового предложения и обычной заявки', () => {
  assert.equal(awaitingDecision(request({ status: 'PENDING' })), true);
  assert.equal(awaitingDecision(request({ status: 'SUCCEEDED' })), true);
  assert.equal(awaitingDecision(request({ status: 'RUNNING' })), false);
  assert.equal(awaitingDecision(request({ status: 'FAILED' })), false);
  assert.equal(awaitingDecision(request({ status: 'APPROVED' })), false);
});

test('F05: классы отказа модели различимы по тексту, а не только по коду', () => {
  const rate = llmError(
    request({ status: 'FAILED', error: 'llm_rate_limited: провайдер ответил 429' }),
  );
  const timeout = llmError(
    request({ status: 'FAILED', error: 'llm_timeout: вызов не уложился во время' }),
  );
  const invalid = llmError(
    request({ status: 'FAILED', error: 'llm_invalid_response: в ответе нет JSON' }),
  );

  assert.ok(rate && timeout && invalid);
  assert.equal(rate.key, 'request.error.llm_rate_limited');
  assert.equal(rate.detail, 'провайдер ответил 429');
  assert.equal(timeout.key, 'request.error.llm_timeout');
  assert.equal(invalid.key, 'request.error.llm_invalid_response');

  const texts = [rate.key, timeout.key, invalid.key].map((key) => translate(key));
  assert.equal(new Set(texts).size, 3, 'три класса отказа обязаны выглядеть по-разному (D-043)');
});

test('F05: у незавершённой заявки нет ошибки модели', () => {
  assert.equal(llmError(request({ status: 'RUNNING', error: null })), null);
  assert.equal(llmError(request({ status: 'SUCCEEDED', error: 'llm_timeout' })), null);
});

test('F05: дифф правки персонажа показывает прежнее и новое значение', () => {
  const lines = diffOf(
    operation({ oldValue: { value: 'высокий' }, payload: { field: 'appearance', value: 'седой' } }),
  );

  assert.equal(lines.length, 1);
  const [line] = lines;
  assert.ok(line);
  assert.equal(line.labelKey, 'request.field.appearance');
  assert.equal(line.before, 'высокий');
  assert.equal(line.after, 'седой');
});

test('F05: у неприменённой операции прежнего значения нет и подсказка не выдумывается', () => {
  const lines = diffOf(
    operation({ op: 'create_item', payload: { name: 'Меч', size: 'SMALL' }, oldValue: null }),
  );

  const [line] = lines;
  assert.ok(line);
  assert.equal(line.labelKey, 'request.diff.item');
  assert.equal(line.before, null);
  assert.equal(line.after, 'Меч, SMALL');
});

test('F05: неизвестная операция не ломает панель', () => {
  const lines = diffOf(operation({ op: 'future_op', payload: { anything: 1 } }));

  const [line] = lines;
  assert.ok(line);
  assert.equal(line.labelKey, 'request.diff.other');
  assert.equal(line.after, '{"anything":1}');
});
