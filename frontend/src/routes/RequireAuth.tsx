import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useSession } from '../session/SessionProvider';
import { translate } from '../i18n/ru';
import { guardDestination } from './guard';

/**
 * Guard стоит на маршруте, а не на навигации: сессия живёт в `HttpOnly` cookie, поэтому
 * после перезапуска приложения первый ответ `GET /me` и есть настоящая проверка входа.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useSession();
  const location = useLocation();
  const decision = guardDestination(status, location.pathname);

  if (decision.kind === 'wait') {
    return (
      <p className="loading" role="status">
        {translate('app.loading')}
      </p>
    );
  }

  if (decision.kind === 'redirect') {
    return <Navigate to={decision.to} replace state={decision.state} />;
  }

  return <>{children}</>;
}
