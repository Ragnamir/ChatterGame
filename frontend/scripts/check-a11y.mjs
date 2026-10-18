#!/usr/bin/env node
/**
 * Статические проверки доступности (`PLAN F01`): разметка, а не поведение.
 *
 * Поведение клавиатуры проверяется вручную в браузере и записано в `STATUS.md`. Здесь
 * держится то, что ломается молча при рефакторинге: поле без подписи, положительный
 * `tabindex`, отсутствие `main` или языка документа. Проверки намеренно читают исходник:
 * они не зависят от сборки и падают адресно на строке.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const SRC = join(ROOT, 'src');

function files(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    return statSync(full).isDirectory() ? files(full) : [full];
  });
}

const problems = [];

const html = readFileSync(join(ROOT, 'index.html'), 'utf8');
if (!/<html lang="ru">/.test(html)) {
  problems.push('index.html: нужен <html lang="ru">, иначе экранный диктор читает по-английски');
}
if (!/name="viewport"/.test(html)) {
  problems.push('index.html: нет meta viewport');
}
if (!/name="theme-color"/.test(html)) {
  problems.push('index.html: нет meta theme-color (нужен для установки PWA)');
}

const sources = files(SRC).filter((file) => /\.tsx$/.test(file));
let mainCount = 0;
let skipLink = false;

for (const file of sources) {
  const text = readFileSync(file, 'utf8');
  const where = relative(ROOT, file);

  if (/<main[\s>]/.test(text)) {
    mainCount += 1;
  }
  if (/className="skip-link"/.test(text)) {
    skipLink = true;
  }

  for (const match of text.matchAll(/tabIndex=\{?(-?\d+)\}?/g)) {
    if (Number(match[1]) > 0) {
      problems.push(`${where}: положительный tabIndex=${match[1]} ломает порядок обхода`);
    }
  }

  const labels = new Set(
    [...text.matchAll(/htmlFor="([^"]+)"/g)].map((match) => match[1]),
  );

  for (const tag of text.matchAll(/<(input|select)\b([^>]*)>/g)) {
    const attrs = tag[2] ?? '';
    const id = /id="([^"]+)"/.exec(attrs)?.[1];
    const labelled = /aria-label=/.test(attrs) || (id !== undefined && labels.has(id));
    if (!labelled) {
      const line = text.slice(0, tag.index ?? 0).split('\n').length;
      problems.push(`${where}:${line}: <${tag[1]}> без label/aria-label`);
    }
  }
}

if (mainCount !== 1) {
  problems.push(`ожидался ровно один <main>, найдено ${mainCount}`);
}
if (!skipLink) {
  problems.push('нет ссылки «перейти к содержимому» (skip-link)');
}

if (problems.length > 0) {
  console.error('a11y check failed:');
  for (const problem of problems) console.error('  ' + problem);
  process.exit(1);
}
console.log('a11y ok: язык, viewport, один main, skip-link, все поля подписаны');
