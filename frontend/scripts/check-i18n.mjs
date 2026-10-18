#!/usr/bin/env node
/**
 * Инвариант `D-081`: кириллица в исходниках фронта допустима только в словаре.
 *
 * Без парсера проверка была бы регуляркой по сырому тексту: она ловит комментарии и
 * `description` манифеста, но пропускает `'\u0430'` и склейку `'При' + 'вет'`, а именно
 * так литерал и просачивается. Здесь снимаются комментарии, строковые литералы и шаблоны
 * токенизатором Node (TypeScript компилятор не нужен), поэтому проверяется то, что реально
 * попадёт в бандл, а не форматирование файла.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripComments } from '../src/lib/sourceText.ts';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const SRC = join(ROOT, 'src');
const DICTIONARY = join(SRC, 'i18n', 'ru.ts');
const CYRILLIC = /[\u0400-\u04FF]/;

function files(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    return statSync(full).isDirectory() ? files(full) : [full];
  });
}

const offenders = [];
for (const file of files(SRC)) {
  if (!/\.(ts|tsx)$/.test(file) || file === DICTIONARY) {
    continue;
  }
  const lines = stripComments(readFileSync(file, 'utf8')).split('\n');
  lines.forEach((line, index) => {
    if (CYRILLIC.test(line)) {
      offenders.push(`${relative(ROOT, file)}:${index + 1}: ${line.trim()}`);
    }
  });
}

if (offenders.length > 0) {
  console.error('Кириллица вне словаря (D-081):');
  for (const line of offenders) console.error('  ' + line);
  process.exit(1);
}

const dictionary = readFileSync(DICTIONARY, 'utf8');
if (!dictionary.includes('as const')) {
  console.error('Словарь должен быть литеральным объектом: теряется тип MessageKey.');
  process.exit(1);
}
const keys = [...dictionary.matchAll(/^ {2}'([^']+)':/gm)].map((match) => match[1]);
const duplicates = keys.filter((key, index) => keys.indexOf(key) !== index);
if (duplicates.length > 0) {
  console.error('Повторяющиеся ключи словаря: ' + [...new Set(duplicates)].join(', '));
  process.exit(1);
}
console.log(`i18n ok: кириллица только в словаре, ключей ${keys.length}`);
