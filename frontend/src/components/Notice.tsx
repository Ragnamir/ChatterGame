import type { ReactNode } from 'react';
import { errorMessage, translate, type MessageKey } from '../i18n/ru';
import { ApiError } from '../api/client';

export function Notice({
  code,
  title,
  children,
  tone = 'error',
}: {
  code?: string | null;
  title?: MessageKey;
  children?: ReactNode;
  tone?: 'error' | 'ok';
}) {
  const heading = title ? translate(title) : translate('aria.error');
  const body = code === undefined ? children : errorMessage(code);
  return (
    <div className={`notice${tone === 'ok' ? ' notice--ok' : ''}`} role={tone === 'error' ? 'alert' : 'status'}>
      <p className="notice__title">{heading}</p>
      {body ? <p className="notice__body">{body}</p> : null}
    </div>
  );
}

export function describeError(error: unknown): string | null {
  if (error instanceof ApiError) {
    return error.code ?? (error.status === 0 ? 'network' : null);
  }
  return 'generic';
}
