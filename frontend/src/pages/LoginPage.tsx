import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../session/SessionProvider';
import { translate } from '../i18n/ru';
import { authApi } from '../api/endpoints';
import { validateEmail, validateLogin, type FieldErrors } from '../lib/validation';
import { Notice, describeError } from '../components/Notice';

export function LoginPage() {
  const { status, login } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [fields, setFields] = useState({ login: '', password: '' });
  const [errors, setErrors] = useState<FieldErrors<'login' | 'password'>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [forgot, setForgot] = useState(false);
  const [resetEmail, setResetEmail] = useState('');
  const [resetError, setResetError] = useState<ReturnType<typeof validateEmail>>(null);
  const [resetSent, setResetSent] = useState(false);
  const [resetBusy, setResetBusy] = useState(false);

  if (status === 'authenticated') {
    const from = (location.state as { from?: string } | null)?.from ?? '/games';
    return <Navigate to={from} replace />;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    const found = validateLogin(fields);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) {
      return;
    }
    setBusy(true);
    try {
      await login(fields);
      navigate('/games', { replace: true });
    } catch (error) {
      setFailure(describeError(error));
    } finally {
      setBusy(false);
    }
  }

  async function onRequestReset(event: FormEvent) {
    event.preventDefault();
    const found = validateEmail(resetEmail);
    setResetError(found);
    if (found) {
      return;
    }
    setResetBusy(true);
    try {
      await authApi.requestPasswordReset(resetEmail.trim());
      setResetSent(true);
    } catch (error) {
      setFailure(describeError(error));
    } finally {
      setResetBusy(false);
    }
  }

  return (
    <div className="auth">
      <section className="auth__card" aria-labelledby="login-heading">
        <p className="eyebrow">{translate('app.name')}</p>
        <h1 id="login-heading">{translate('login.title')}</h1>
        <p>{translate('login.subtitle')}</p>
        {failure ? <Notice code={failure} /> : null}
        <form className="form" noValidate onSubmit={(event) => void onSubmit(event)}>
          <div className="field">
            <label htmlFor="login-login">{translate('login.loginLabel')}</label>
            <input
              id="login-login"
              name="login"
              autoComplete="username"
              value={fields.login}
              aria-invalid={errors.login ? true : undefined}
              aria-describedby={errors.login ? 'login-login-error' : undefined}
              onChange={(event) => setFields({ ...fields, login: event.target.value })}
            />
            {errors.login ? (
              <p className="field__error" id="login-login-error">
                {translate(errors.login)}
              </p>
            ) : null}
          </div>
          <div className="field">
            <label htmlFor="login-password">{translate('login.passwordLabel')}</label>
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              value={fields.password}
              aria-invalid={errors.password ? true : undefined}
              aria-describedby={errors.password ? 'login-password-error' : undefined}
              onChange={(event) => setFields({ ...fields, password: event.target.value })}
            />
            {errors.password ? (
              <p className="field__error" id="login-password-error">
                {translate(errors.password)}
              </p>
            ) : null}
          </div>
          <button className="button button--primary" type="submit" disabled={busy}>
            {busy ? translate('login.submitting') : translate('login.submit')}
          </button>
        </form>
        {forgot ? (
          <form className="form" noValidate onSubmit={(event) => void onRequestReset(event)}>
            <p className="panel__hint">{translate('login.resetHint')}</p>
            <div className="field">
              <label htmlFor="login-reset-email">{translate('login.resetEmailLabel')}</label>
              <input
                id="login-reset-email"
                name="email"
                type="email"
                autoComplete="email"
                maxLength={320}
                value={resetEmail}
                aria-invalid={resetError ? true : undefined}
                aria-describedby={resetError ? 'login-reset-email-error' : undefined}
                onChange={(event) => setResetEmail(event.target.value)}
              />
              {resetError ? (
                <p className="field__error" id="login-reset-email-error">
                  {translate(resetError)}
                </p>
              ) : null}
            </div>
            {resetSent ? (
              <div className="notice notice--ok" role="status">
                <p className="notice__title">{translate('login.resetSent')}</p>
              </div>
            ) : (
              <button className="button button--primary" type="submit" disabled={resetBusy}>
                {resetBusy ? translate('login.resetSubmitting') : translate('login.resetSubmit')}
              </button>
            )}
            <button
              className="button button--ghost"
              type="button"
              onClick={() => {
                setForgot(false);
                setResetSent(false);
                setResetError(null);
              }}
            >
              {translate('login.resetBack')}
            </button>
          </form>
        ) : (
          <button className="button button--link" type="button" onClick={() => setForgot(true)}>
            {translate('login.forgot')}
          </button>
        )}
        <p className="form__footer">
          {translate('login.noAccount')} <Link to="/register">{translate('login.toRegister')}</Link>
        </p>
      </section>
    </div>
  );
}
