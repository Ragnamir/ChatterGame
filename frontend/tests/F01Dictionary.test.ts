import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { errorMessage, ru, translate } from '../src/i18n/ru.ts';
import { stripComments } from '../src/lib/sourceText.ts';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const SRC = join(ROOT, 'src');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    return statSync(full).isDirectory() ? sourceFiles(full) : [full];
  });
}

const KEY_LIKE = /'(app|nav|login|register|common|games|profile|validation|errors|aria)\.[A-Za-z0-9_.]+'/g;

test('F01: each literal key used in the UI exists in the dictionary', () => {
  const known = new Set(Object.keys(ru));
  const missing: string[] = [];
  for (const file of sourceFiles(SRC)) {
    if (!/\.(ts|tsx)$/.test(file)) {
      continue;
    }
    const text = readFileSync(file, 'utf8');
    for (const match of text.matchAll(KEY_LIKE)) {
      const key = match[0].slice(1, -1);
      if (!known.has(key)) {
        missing.push(`${file}: ${key}`);
      }
    }
  }
  assert.deepEqual(missing, [], 'keys referenced but absent from ru.ts');
});

test('F01: dictionary keys are flat and unique', () => {
  const keys = Object.keys(ru);
  assert.equal(new Set(keys).size, keys.length);
  for (const key of keys) {
    assert.match(key, /^[a-z]+(\.[A-Za-z0-9_]+)+$/, key);
  }
});

test('F01: translate substitutes named placeholders and keeps unknown ones', () => {
  assert.equal(translate('games.resultCount', { count: 3 }), 'Найдено игр: 3');
  assert.equal(
    translate('games.pausedReason', { reason: 'отпуск' }),
    'Причина паузы: отпуск',
  );
  assert.equal(translate('games.title'), 'Мои игры');
});

test('F01: server codes map to messages, unknown codes fall back to generic', () => {
  assert.equal(errorMessage('invalid_credentials'), 'Неверный логин или пароль.');
  assert.equal(errorMessage('login_taken'), 'Такой логин уже занят.');
  assert.equal(errorMessage('something_new'), ru['errors.generic']);
  assert.equal(errorMessage(null), ru['errors.generic']);
});

test('F01: no Cyrillic outside the dictionary (tokenized source)', () => {
  const dictionary = join(SRC, 'i18n', 'ru.ts');
  const offenders: string[] = [];
  for (const file of sourceFiles(SRC)) {
    if (!/\.(ts|tsx)$/.test(file) || file === dictionary) {
      continue;
    }
    stripComments(readFileSync(file, 'utf8'))
      .split('\n')
      .forEach((line, index) => {
        if (/[\u0400-\u04FF]/.test(line)) {
          offenders.push(`${file}:${index + 1}`);
        }
      });
  }
  assert.deepEqual(offenders, []);
});
