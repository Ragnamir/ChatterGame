import { useState, type FormEvent } from 'react';
import { profileApi } from '../api/endpoints';
import type { ThemePreference } from '../api/types';
import { Notice, describeError } from '../components/Notice';
import { translate } from '../i18n/ru';
import { applyTheme, storeTheme } from '../lib/theme';
import { emptyToNull } from '../lib/validation';
import { useSession } from '../session/SessionProvider';

const THEMES: readonly ThemePreference[] = ['system', 'light', 'dark'];
const THEME_LABEL = {
  system: 'profile.theme.system',
  light: 'profile.theme.light',
  dark: 'profile.theme.dark',
} as const;

export function ProfilePage() {
  const { user, updateUser } = useSession();
  const [displayName, setDisplayName] = useState(user?.displayName ?? '');
  const [email, setEmail] = useState(user?.email ?? '');
  const [theme, setTheme] = useState<ThemePreference>(user?.theme ?? 'system');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [resendBusy, setResendBusy] = useState(false);
  const [resendSent, setResendSent] = useState(false);

  if (!user) {
    return null;
  }

  async function onResend(event: FormEvent) {
    event.preventDefault();
    setResendBusy(true);
    setResendSent(false);
    try {
      await profileApi.resendEmailVerification();
      setResendSent(true);
    } catch (error) {
      setResendSent(false);
      setFailure(describeError(error));
    } finally {
      setResendBusy(false);
    }
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setFailure(null);
    setSaved(false);
    try {
      const updated = await profileApi.update({
        displayName: emptyToNull(displayName),
        email: emptyToNull(email),
        theme,
      });
      updateUser(updated);
      setSaved(true);
    } catch (error) {
      setFailure(describeError(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page__header">
        <h1 className="page__title">{translate('profile.title')}</h1>
      </div>
      <p className="page__subtitle">{translate('profile.subtitle')}</p>

      {failure ? <Notice code={failure} /> : null}
      {saved ? (
        <div className="notice notice--ok" role="status">
          <p className="notice__title">{translate('profile.saved')}</p>
        </div>
      ) : null}

      <section className="panel" aria-label={translate('profile.loginLabel')}>
        <dl className="readout">
          <dt>{translate('profile.loginLabel')}</dt>
          <dd>{user.login}</dd>
        </dl>
      </section>

      <div className="profile-grid">
        <section className="panel" aria-labelledby="profile-avatar">
          <h2 id="profile-avatar">{translate('profile.avatarTitle')}</h2>
          <p className="panel__hint">{translate('profile.avatarHint')}</p>
        </section>

        <section className="panel" aria-labelledby="profile-llm">
          <h2 id="profile-llm">{translate('profile.llmTitle')}</h2>
          <p className="panel__hint">{translate('profile.llmHint')}</p>
          <div className="field">
            <label htmlFor="profile-llm-field">{translate('profile.llmFieldLabel')}</label>
            <input id="profile-llm-field" type="password" autoComplete="off" disabled />
          </div>
        </section>
      </div>

      <form className="panel" noValidate onSubmit={(event) => void onSubmit(event)}>
        <div className="form">
          <div className="field">
            <label htmlFor="profile-display-name">{translate('profile.displayNameLabel')}</label>
            <input
              id="profile-display-name"
              name="displayName"
              autoComplete="nickname"
              maxLength={128}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="profile-email">{translate('profile.emailLabel')}</label>
            <input
              id="profile-email"
              name="email"
              type="email"
              autoComplete="email"
              maxLength={320}
              value={email}
              aria-describedby="profile-email-hint"
              onChange={(event) => setEmail(event.target.value)}
            />
            <p className="field__hint" id="profile-email-hint">
              {translate('profile.emailHint')}
            </p>
            {user.email ? (
              <div className="profile-verification">
                {user.emailVerified ? (
                  <p className="field__hint">{translate('profile.emailVerified')}</p>
                ) : (
                  <>
                    <p className="field__hint">{translate('profile.emailUnverified')}</p>
                    <button
                      className="button button--ghost"
                      type="button"
                      disabled={resendBusy}
                      onClick={(event) => void onResend(event)}
                    >
                      {resendBusy
                        ? translate('profile.emailVerificationSending')
                        : translate('profile.emailVerificationResend')}
                    </button>
                    {resendSent ? (
                      <p className="notice notice--ok" role="status">
                        {translate('profile.emailVerificationSent')}
                      </p>
                    ) : null}
                  </>
                )}
              </div>
            ) : null}
          </div>
          <div className="field">
            <label htmlFor="profile-theme">{translate('profile.themeLabel')}</label>
            <select
              id="profile-theme"
              value={theme}
              onChange={(event) => {
                const next = event.target.value as ThemePreference;
                setTheme(next);
                storeTheme(next);
                applyTheme(next);
              }}
            >
              {THEMES.map((value) => (
                <option key={value} value={value}>
                  {translate(THEME_LABEL[value])}
                </option>
              ))}
            </select>
          </div>
          <div className="form__actions">
            <button className="button button--primary" type="submit" disabled={busy}>
              {busy ? translate('profile.saving') : translate('profile.save')}
            </button>
          </div>
        </div>
      </form>
    </>
  );
}
