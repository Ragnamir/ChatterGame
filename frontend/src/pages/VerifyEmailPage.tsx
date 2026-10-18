import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { Notice, describeError } from '../components/Notice';
import { translate } from '../i18n/ru';
import { useSession } from '../session/SessionProvider';

/**
 * Переход по ссылке подтверждения адреса (`O01`, `D-141`). Подтверждение отправляется
 * один раз: повторный запрос при перерисовке погасил бы ссылку и показал бы ошибку на
 * успешном сценарии.
 */
export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const { refresh } = useSession();
  const [state, setState] = useState<'pending' | 'done' | 'failed'>('pending');
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    if (token === '') {
      setState('failed');
      setFailure('invalid_email_token');
      return;
    }
    let cancelled = false;
    authApi
      .verifyEmail(token)
      .then(() => {
        if (!cancelled) {
          setState('done');
          void refresh();
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setState('failed');
          setFailure(describeError(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [token, refresh]);

  return (
    <div className="auth">
      <section className="auth__card" aria-labelledby="verify-heading">
        <p className="eyebrow">{translate('app.name')}</p>
        <h1 id="verify-heading">{translate('verify.title')}</h1>
        {state === 'pending' ? <p>{translate('verify.pending')}</p> : null}
        {state === 'done' ? (
          <div className="notice notice--ok" role="status">
            <p className="notice__title">{translate('verify.done')}</p>
            <p className="notice__body">{translate('verify.doneHint')}</p>
          </div>
        ) : null}
        {state === 'failed' ? <Notice code={failure} /> : null}
        {state !== 'pending' ? (
          <p className="form__footer">
            <Link to="/profile">{translate('verify.toProfile')}</Link>
          </p>
        ) : null}
      </section>
    </div>
  );
}
