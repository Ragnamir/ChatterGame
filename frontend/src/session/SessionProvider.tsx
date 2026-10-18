import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { authApi } from '../api/endpoints';
import type { LoginInput, RegisterInput, UserProfile } from '../api/types';
import { applyTheme, readStoredTheme, storeTheme } from '../lib/theme';

export type SessionStatus = 'loading' | 'anonymous' | 'authenticated';

export interface SessionValue {
  status: SessionStatus;
  user: UserProfile | null;
  login: (input: LoginInput) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  logout: () => Promise<void>;
  updateUser: (user: UserProfile) => void;
  refresh: () => Promise<void>;
}

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [user, setUser] = useState<UserProfile | null>(null);

  const adopt = useCallback((profile: UserProfile) => {
    setUser(profile);
    setStatus('authenticated');
    storeTheme(profile.theme);
    applyTheme(profile.theme);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const profile = await authApi.me();
      adopt(profile);
    } catch {
      // `401` — обычное «не вошёл»; `0`/`5xx` — сервер недоступен. В обоих случаях
      // показываем вход, а не вечную загрузку, и не бросаем из эффекта наружу.
      setUser(null);
      setStatus('anonymous');
    }
  }, [adopt]);

  useEffect(() => {
    applyTheme(readStoredTheme());
    void refresh();
  }, [refresh]);

  const login = useCallback(
    async (input: LoginInput) => {
      adopt(await authApi.login(input));
    },
    [adopt],
  );

  const register = useCallback(
    async (input: RegisterInput) => {
      adopt(await authApi.register(input));
    },
    [adopt],
  );

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setUser(null);
      setStatus('anonymous');
    }
  }, []);

  const value = useMemo<SessionValue>(
    () => ({ status, user, login, register, logout, updateUser: adopt, refresh }),
    [status, user, login, register, logout, adopt, refresh],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('useSession outside SessionProvider');
  }
  return session;
}
