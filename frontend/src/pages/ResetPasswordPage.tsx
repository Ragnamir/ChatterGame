import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { Notice, describeError } from '../components/Notice';
import { translate } from '../i18n/ru';
import { validateNewPassword, type FieldErrors } from '../lib/validation';

type Field = 'password' | 'repeat';

/**
 * Новый пароль по ссылке из письма (`O01`). Токен берётся из адресной строки, а не из
 * состояния: ссылку открывают в новом окне, и ничего, кроме `token`, в ней нет.
 */
export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [fields, setFields] = useState({ password: '', repeat: '' });
  const [errors, setErrors] = useState<FieldErrors<Field>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    const found: FieldErrors<Field> = {};
    const passwordError = validateNewPassword(fields.password);
    if (passwordError) {
      found.password = passwordError;
    }
    if (fields.repeat !== fields.password) {
      found.repeat = 'validation.passwordMismatch';
    }
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) {
      return;
    }
    setBusy(true);
    try {
      await authApi.confirmPasswordReset(token, fields.password);
      setDone(true);
    } catch (error) {
      setFailure(describeError(error));
    } finally {
      setBusy(false);
    }
  }

  if (token === '') {
    return (
      <div className="auth">
        <section className="auth__card" aria-labelledby="reset-heading">
          <h1 id="reset-heading">{translate('reset.title')}</h1>
          <Notice code="invalid_reset_token" />
          <p className="form__footer">
            <Link to="/login">{translate('reset.toLogin')}</Link>
          </p>
        </section>
      </div>
    );
  }

  return (
    <div className="auth">
      <section className="auth__card" aria-labelledby="reset-heading">
        <p className="eyebrow">{translate('app.name')}</p>
        <h1 id="reset-heading">{translate('reset.title')}</h1>
        <p>{translate('reset.subtitle')}</p>
        {failure ? <Notice code={failure} /> : null}
        {done ? (
          <>
            <div className="notice notice--ok" role="status">
              <p className="notice__title">{translate('reset.done')}</p>
              <p className="notice__body">{translate('reset.doneHint')}</p>
            </div>
            <p className="form__footer">
              <Link to="/login">{translate('reset.toLogin')}</Link>
            </p>
          </>
        ) : (
          <form className="form" noValidate onSubmit={(event) => void onSubmit(event)}>
            <div className="field">
              <label htmlFor="reset-password">{translate('reset.passwordLabel')}</label>
              <input
                id="reset-password"
                name="password"
                type="password"
                autoComplete="new-password"
                value={fields.password}
                aria-invalid={errors.password ? true : undefined}
                aria-describedby={errors.password ? 'reset-password-error' : 'reset-password-hint'}
                onChange={(event) => setFields({ ...fields, password: event.target.value })}
              />
              {errors.password ? (
                <p className="field__error" id="reset-password-error">
                  {translate(errors.password)}
                </p>
              ) : (
                <p className="field__hint" id="reset-password-hint">
                  {translate('reset.passwordHint')}
                </p>
              )}
            </div>
            <div className="field">
              <label htmlFor="reset-repeat">{translate('reset.repeatLabel')}</label>
              <input
                id="reset-repeat"
                name="repeat"
                type="password"
                autoComplete="new-password"
                value={fields.repeat}
                aria-invalid={errors.repeat ? true : undefined}
                aria-describedby={errors.repeat ? 'reset-repeat-error' : undefined}
                onChange={(event) => setFields({ ...fields, repeat: event.target.value })}
              />
              {errors.repeat ? (
                <p className="field__error" id="reset-repeat-error">
                  {translate(errors.repeat)}
                </p>
              ) : null}
            </div>
            <button className="button button--primary" type="submit" disabled={busy}>
              {busy ? translate('reset.submitting') : translate('reset.submit')}
            </button>
          </form>
        )}
      </section>
    </div>
  );
}
