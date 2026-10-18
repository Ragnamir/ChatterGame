#!/usr/bin/env node
/**
 * Проверка установки PWA по собранному `dist`, а не по настройкам сборщика (`PLAN F01`).
 * Настройка `VitePWA` может быть верной, а манифест или иконки — не попасть в артефакт:
 * тогда «установка» проходит в браузере, но на телефоне иконка серая. Здесь читается то,
 * что реально уедет в прод.
 */
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const DIST = join(ROOT, 'dist');

const problems = [];
const require_ = (condition, message) => {
  if (!condition) problems.push(message);
};

/** Размер PNG берётся из IHDR: без ImageMagick и без зависимостей. */
function pngSize(file) {
  const buffer = readFileSync(file);
  const signature = buffer.subarray(0, 8).toString('hex');
  if (signature !== '89504e470d0a1a0a') {
    return null;
  }
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

if (!existsSync(DIST)) {
  console.error('PWA check failed: нет dist, сначала vite build');
  process.exit(1);
}

const manifestPath = join(DIST, 'manifest.webmanifest');
require_(existsSync(manifestPath), 'dist/manifest.webmanifest отсутствует');
if (existsSync(manifestPath)) {
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  require_(manifest.name && manifest.short_name, 'манифест без name/short_name');
  require_(manifest.lang === 'ru', 'манифест без lang=ru');
  require_(manifest.display === 'standalone', 'манифест без display=standalone');
  require_(manifest.start_url === '/' || manifest.start_url === './', 'start_url не задан');
  require_(manifest.theme_color, 'манифест без theme_color');
  const icons = manifest.icons ?? [];
  const sizes = new Set(icons.map((icon) => icon.sizes));
  require_(sizes.has('192x192'), 'манифест без иконки 192x192');
  require_(sizes.has('512x512'), 'манифест без иконки 512x512');
  require_(
    icons.some((icon) => (icon.purpose ?? '').includes('maskable')),
    'манифест без maskable-иконки',
  );

  for (const icon of icons) {
    const file = join(DIST, icon.src.replace(/^\//, ''));
    require_(existsSync(file), `иконка ${icon.src} не попала в dist`);
  }
}

for (const [name, width, height] of [
  ['icon-192.png', 192, 192],
  ['icon-512.png', 512, 512],
  ['icon-maskable-512.png', 512, 512],
  ['apple-touch-icon.png', 180, 180],
]) {
  const file = join(DIST, 'icons', name);
  if (!existsSync(file)) {
    problems.push(`dist/icons/${name} отсутствует`);
    continue;
  }
  const size = pngSize(file);
  require_(size !== null, `${name} не PNG`);
  if (size) {
    require_(
      size.width === width && size.height === height,
      `${name}: ${size.width}x${size.height}, ожидалось ${width}x${height}`,
    );
  }
}

const indexHtml = readFileSync(join(DIST, 'index.html'), 'utf8');
require_(/name="viewport"/.test(indexHtml), 'index.html без viewport');
require_(/rel="apple-touch-icon"/.test(indexHtml), 'index.html без apple-touch-icon');
require_(
  /registerSW\.js|serviceWorker/.test(indexHtml),
  'index.html не регистрирует service worker',
);

require_(existsSync(join(DIST, 'sw.js')), 'dist/sw.js отсутствует: приложение не установится офлайн');
if (existsSync(join(DIST, 'sw.js'))) {
  const sw = readFileSync(join(DIST, 'sw.js'), 'utf8');
  require_(sw.length > 0, 'sw.js пуст');
  require_(/index\.html/.test(sw), 'sw.js не кэширует оболочку приложения');
}

if (problems.length > 0) {
  console.error('PWA check failed:');
  for (const problem of problems) console.error('  ' + problem);
  process.exit(1);
}
console.log('PWA ok: манифест, иконки нужных размеров и service worker на месте');
