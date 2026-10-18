import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  emptyToNull,
  validateLogin,
  validateRegister,
} from '../src/lib/validation.ts';
import { guardDestination } from '../src/routes/guard.ts';
import { resolveTheme } from '../src/lib/theme.ts';

test('F01: login rejects empty fields before any request', () => {
  const errors = validateLogin({ login: '  ', password: '' });
  assert.equal(errors.login, 'validation.loginRequired');
  assert.equal(errors.password, 'validation.passwordRequired');
  assert.deepEqual(validateLogin({ login: 'ann', password: 'secret12' }), {});
});

test('F01: registration enforces the server password floor and email shape', () => {
  const errors = validateRegister({
    login: 'ann',
    password: 'short',
    displayName: 'x'.repeat(129),
    email: 'not-an-email',
  });
  assert.equal(errors.password, 'validation.passwordTooShort');
  assert.equal(errors.displayName, 'validation.displayNameTooLong');
  assert.equal(errors.email, 'validation.emailInvalid');
  assert.deepEqual(
    validateRegister({ login: 'ann', password: 'longenough', displayName: '', email: '' }),
    {},
  );
});

test('F01: blank optional fields become null so PATCH does not erase values with spaces', () => {
  assert.equal(emptyToNull('   '), null);
  assert.equal(emptyToNull('  Анна '), 'Анна');
});

test('F01: a loading session waits instead of redirecting to login', () => {
  assert.deepEqual(guardDestination('loading', '/games'), { kind: 'wait' });
});

test('F01: an anonymous session returns to login and remembers the target', () => {
  assert.deepEqual(guardDestination('anonymous', '/profile'), {
    kind: 'redirect',
    to: '/login',
    state: { from: '/profile' },
  });
});

test('F01: an authenticated session opens the guarded page', () => {
  assert.deepEqual(guardDestination('authenticated', '/games'), { kind: 'allow' });
});

test('F01: theme preference follows the system only when set to system', () => {
  assert.equal(resolveTheme('system', true), 'dark');
  assert.equal(resolveTheme('system', false), 'light');
  assert.equal(resolveTheme('light', true), 'light');
  assert.equal(resolveTheme('dark', false), 'dark');
});
