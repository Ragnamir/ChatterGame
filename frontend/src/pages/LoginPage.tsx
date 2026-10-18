import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../session/SessionProvider';
import { translate } from '../i18n/ru';
import { validateLogin, type FieldErrors } from '../lib/validation';
import { Notice, describeError } from '../components/Notice';

export function LoginPage() {
  const { status, login } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [fields, setFields] = useState({ login: '', password: '' });
  const [errors, setErrors] = useState<FieldErrors<'login' | 'password'>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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
        <p className="form__footer">
          {translate('login.noAccount')} <Link to="/register">{translate('login.toRegister')}</Link>
        </p>
      </section>
    </div>
  );
}
