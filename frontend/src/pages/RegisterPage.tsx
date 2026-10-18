import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useSession } from '../session/SessionProvider';
import { translate } from '../i18n/ru';
import { emptyToNull, validateRegister, type FieldErrors } from '../lib/validation';
import { Notice, describeError } from '../components/Notice';

type Field = 'login' | 'password' | 'displayName' | 'email';

export function RegisterPage() {
  const { status, register } = useSession();
  const navigate = useNavigate();
  const [fields, setFields] = useState({ login: '', password: '', displayName: '', email: '' });
  const [errors, setErrors] = useState<FieldErrors<Field>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (status === 'authenticated') {
    return <Navigate to="/games" replace />;
  }

  function update(field: Field, value: string) {
    setFields((previous) => ({ ...previous, [field]: value }));
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    const found = validateRegister(fields);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) {
      return;
    }
    setBusy(true);
    try {
      await register({
        login: fields.login.trim(),
        password: fields.password,
        displayName: emptyToNull(fields.displayName),
        email: emptyToNull(fields.email),
      });
      navigate('/games', { replace: true });
    } catch (error) {
      setFailure(describeError(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <section className="auth__card" aria-labelledby="register-heading">
        <p className="eyebrow">{translate('app.name')}</p>
        <h1 id="register-heading">{translate('register.title')}</h1>
        <p>{translate('register.subtitle')}</p>
        {failure ? <Notice code={failure} /> : null}
        <form className="form" noValidate onSubmit={(event) => void onSubmit(event)}>
          <div className="field">
            <label htmlFor="register-login">{translate('register.loginLabel')}</label>
            <input
              id="register-login"
              name="login"
              autoComplete="username"
              value={fields.login}
              aria-invalid={errors.login ? true : undefined}
              aria-describedby={errors.login ? 'register-login-error' : undefined}
              onChange={(event) => update('login', event.target.value)}
            />
            {errors.login ? (
              <p className="field__error" id="register-login-error">
                {translate(errors.login)}
              </p>
            ) : null}
          </div>
          <div className="field">
            <label htmlFor="register-display-name">{translate('register.displayNameLabel')}</label>
            <input
              id="register-display-name"
              name="displayName"
              autoComplete="nickname"
              value={fields.displayName}
              aria-invalid={errors.displayName ? true : undefined}
              aria-describedby={errors.displayName ? 'register-display-name-error' : undefined}
              onChange={(event) => update('displayName', event.target.value)}
            />
            {errors.displayName ? (
              <p className="field__error" id="register-display-name-error">
                {translate(errors.displayName)}
              </p>
            ) : null}
          </div>
          <div className="field">
            <label htmlFor="register-email">
              {translate('register.emailLabel')}{' '}
              <span className="field__hint">({translate('common.optional')})</span>
            </label>
            <input
              id="register-email"
              name="email"
              type="email"
              autoComplete="email"
              value={fields.email}
              aria-invalid={errors.email ? true : undefined}
              aria-describedby={errors.email ? 'register-email-error' : undefined}
              onChange={(event) => update('email', event.target.value)}
            />
            {errors.email ? (
              <p className="field__error" id="register-email-error">
                {translate(errors.email)}
              </p>
            ) : null}
          </div>
          <div className="field">
            <label htmlFor="register-password">{translate('register.passwordLabel')}</label>
            <input
              id="register-password"
              name="password"
              type="password"
              autoComplete="new-password"
              value={fields.password}
              aria-invalid={errors.password ? true : undefined}
              aria-describedby={
                errors.password ? 'register-password-error' : 'register-password-hint'
              }
              onChange={(event) => update('password', event.target.value)}
            />
            {errors.password ? (
              <p className="field__error" id="register-password-error">
                {translate(errors.password)}
              </p>
            ) : (
              <p className="field__hint" id="register-password-hint">
                {translate('register.passwordHint')}
              </p>
            )}
          </div>
          <button className="button button--primary" type="submit" disabled={busy}>
            {busy ? translate('register.submitting') : translate('register.submit')}
          </button>
        </form>
        <p className="form__footer">
          {translate('register.haveAccount')}{' '}
          <Link to="/login">{translate('register.toLogin')}</Link>
        </p>
      </section>
    </div>
  );
}
