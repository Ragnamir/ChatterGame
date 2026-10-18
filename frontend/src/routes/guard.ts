import type { SessionStatus } from '../session/SessionProvider';

export type GuardDecision =
  | { kind: 'wait' }
  | { kind: 'redirect'; to: string; state: { from: string } }
  | { kind: 'allow' };

/**
 * Решение вынесено из компонента: пока сессия грузится, редирект на вход был бы ложным
 * «вы не вошли» при каждом перезапуске PWA. Ждём ответ `GET /me`, затем решаем.
 */
export function guardDestination(status: SessionStatus, pathname: string): GuardDecision {
  if (status === 'loading') {
    return { kind: 'wait' };
  }
  if (status === 'anonymous') {
    return { kind: 'redirect', to: '/login', state: { from: pathname } };
  }
  return { kind: 'allow' };
}
