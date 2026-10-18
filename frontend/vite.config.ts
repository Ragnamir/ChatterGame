import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import { ru } from './src/i18n/ru';

// API живёт на том же origin, что и приложение: cookie сессии `HttpOnly` и `SameSite`,
// поэтому отдельный домен фронта потребовал бы CORS с credentialed-запросами, а
// `HttpOnly` cookie всё равно не читается из JS. В разработке тот же контур даёт proxy.
// Маршруты вида `/games` совпадают с путями API, поэтому переход браузера (Accept: text/html)
// отдаётся оболочке SPA, а запрос клиента (Accept: application/json) уходит на сервер.
const API_TARGET = 'http://localhost:8080';
const API_PATHS = ['/auth', '/me', '/games', '/ws'] as const;
const apiProxy = Object.fromEntries(
  API_PATHS.map((path) => [
    path,
    {
      target: API_TARGET,
      ws: true,
      bypass: (request: { headers: { accept?: string } }) =>
        request.headers.accept?.includes('text/html') ? '/index.html' : undefined,
    },
  ]),
);

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icons/apple-touch-icon.png'],
      manifest: {
        name: 'ChatterGame',
        short_name: 'ChatterGame',
        description: ru['app.description'],
        lang: 'ru',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#12100e',
        theme_color: '#12100e',
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          {
            src: '/icons/icon-maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        navigateFallback: '/index.html',
        // API и сокет не должны попадать в оболочку: кэшированный `401` или старая лента
        // выглядели бы как данные, а не как отсутствие связи.
        navigateFallbackDenylist: [/^\/(auth|me|games|ws)(\/|$)/],
        runtimeCaching: [
          {
            urlPattern: ({ request }) => request.mode === 'navigate',
            handler: 'NetworkFirst',
            options: {
              cacheName: 'pages',
              networkTimeoutSeconds: 3,
            },
          },
        ],
      },
      devOptions: { enabled: false },
    }),
  ],
  server: { proxy: apiProxy },
});
