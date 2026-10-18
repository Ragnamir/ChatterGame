import type { MessageKey } from '../i18n/ru';

export type FieldErrors<T extends string> = Partial<Record<T, MessageKey>>;

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export interface LoginFields {
  login: string;
  password: string;
}

export function validateLogin(fields: LoginFields): FieldErrors<keyof LoginFields> {
  const errors: FieldErrors<keyof LoginFields> = {};
  if (fields.login.trim() === '') {
    errors.login = 'validation.loginRequired';
  }
  if (fields.password === '') {
    errors.password = 'validation.passwordRequired';
  }
  return errors;
}

export interface RegisterFields {
  login: string;
  password: string;
  displayName: string;
  email: string;
}

export function validateRegister(fields: RegisterFields): FieldErrors<keyof RegisterFields> {
  const errors: FieldErrors<keyof RegisterFields> = {};
  if (fields.login.trim() === '') {
    errors.login = 'validation.loginRequired';
  }
  if (fields.password === '') {
    errors.password = 'validation.passwordRequired';
  } else if (fields.password.length < 8) {
    errors.password = 'validation.passwordTooShort';
  }
  if (fields.displayName.trim().length > 128) {
    errors.displayName = 'validation.displayNameTooLong';
  }
  if (fields.email.trim() !== '' && !EMAIL.test(fields.email.trim())) {
    errors.email = 'validation.emailInvalid';
  }
  return errors;
}

export function validateEmail(email: string): MessageKey | null {
  if (email.trim() === '') {
    return 'validation.emailRequired';
  }
  return EMAIL.test(email.trim()) ? null : 'validation.emailInvalid';
}

export function validateNewPassword(password: string): MessageKey | null {
  if (password === '') {
    return 'validation.passwordRequired';
  }
  return password.length < 8 ? 'validation.passwordTooShort' : null;
}

/**
 * Пустая строка у необязательного поля означает «нет значения», а не «пустое имя»:
 * сервер хранит `null`, иначе `PATCH` с пустым полем затирал бы имя на пробел.
 */
export function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

