import type { ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useSession } from '../session/SessionProvider';
import { translate } from '../i18n/ru';

export function Layout({ children }: { children: ReactNode }) {
  const { status, user, logout } = useSession();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        {translate('app.skipToContent')}
      </a>
      <header className="masthead">
        <div className="masthead__inner">
          <NavLink className="wordmark" to={status === 'authenticated' ? '/games' : '/login'}>
            Chatter<span>Game</span>
          </NavLink>
          {status === 'authenticated' ? (
            <nav className="main-nav" aria-label={translate('nav.label')}>
              <NavLink to="/games">{translate('nav.games')}</NavLink>
              <NavLink to="/profile">{translate('nav.profile')}</NavLink>
              <button type="button" onClick={() => void handleLogout()}>
                {translate('nav.logout')}
              </button>
              <span className="sr-only">{user?.displayName}</span>
            </nav>
          ) : null}
        </div>
      </header>
      <main id="main" className="page">
        {children}
      </main>
    </div>
  );
}
