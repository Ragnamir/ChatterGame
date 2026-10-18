import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateEmail, validateNewPassword } from '../src/lib/validation.ts';
import { errorMessage } from '../src/i18n/ru.ts';

test('O01: reset form refuses an empty address before any request', () => {
  assert.equal(validateEmail('   '), 'validation.emailRequired');
  assert.equal(validateEmail('not-an-email'), 'validation.emailInvalid');
  assert.equal(validateEmail('player@example.org'), null);
});

test('O01: new password honours the server floor', () => {
  assert.equal(validateNewPassword(''), 'validation.passwordRequired');
  assert.equal(validateNewPassword('short'), 'validation.passwordTooShort');
  assert.equal(validateNewPassword('longenough'), null);
});

test('O01: mail failures are shown by machine code, not as a generic error', () => {
  assert.equal(
    errorMessage('invalid_email_token'),
    'Ссылка подтверждения недействительна или истекла.',
  );
  assert.equal(errorMessage('invalid_reset_token'), 'Ссылка сброса недействительна или истекла.');
  assert.equal(
    errorMessage('mail_unavailable'),
    'Почта недоступна: письмо не отправлено, попробуйте позже.',
  );
  assert.equal(errorMessage('rate_limited'), 'Слишком много попыток. Попробуйте позже.');
});

test('O01: enumeration-safe wording does not reveal whether the address exists', () => {
  assert.equal(errorMessage('unknown_address'), errorMessage('generic'));
});
