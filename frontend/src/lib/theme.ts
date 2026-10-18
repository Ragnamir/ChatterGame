import type { ThemePreference } from '../api/types';

export type ResolvedTheme = 'light' | 'dark';

const STORAGE_KEY = 'chattergame.theme';

/**
 * Тема выбирается на профиле, но применяется до входа: экран входа тоже должен совпадать
 * с системой. `system` читает `prefers-color-scheme`, явный выбор перекрывает его.
 */
export function resolveTheme(preference: ThemePreference, systemDark: boolean): ResolvedTheme {
  if (preference === 'light') {
    return 'light';
  }
  if (preference === 'dark') {
    return 'dark';
  }
  return systemDark ? 'dark' : 'light';
}

export function readStoredTheme(): ThemePreference {
  const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
  return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
}

export function storeTheme(preference: ThemePreference): void {
  globalThis.localStorage?.setItem(STORAGE_KEY, preference);
}

export function applyTheme(preference: ThemePreference): ResolvedTheme {
  const media = globalThis.matchMedia?.('(prefers-color-scheme: dark)');
  const resolved = resolveTheme(preference, media?.matches ?? false);
  globalThis.document?.documentElement.setAttribute('data-theme', resolved);
  return resolved;
}
