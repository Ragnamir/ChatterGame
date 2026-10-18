import { api, query } from './client';
import type {
  Channel,
  ChannelMember,
  ChatEntry,
  Character,
  Feed,
  FeedItem,
  Game,
  GameGraph,
  LoginInput,
  Membership,
  Placement,
  PostMessageInput,
  RegisterInput,
  Restriction,
  RestrictionKind,
  Turn,
  Unread,
  UpdateProfileInput,
  UserProfile,
} from './types';

export const authApi = {
  me: (): Promise<UserProfile> => api.get('/me'),
  login: (input: LoginInput): Promise<UserProfile> => api.post('/auth/login', input),
  register: (input: RegisterInput): Promise<UserProfile> => api.post('/auth/register', input),
  logout: (): Promise<void> => api.post('/auth/logout'),
};

export const gamesApi = {
  listMine: (): Promise<Game[]> => api.get('/games'),
  read: (gameId: string): Promise<Game> => api.get(`/games/${gameId}`),
};

export const profileApi = {
  update: (input: UpdateProfileInput): Promise<UserProfile> => api.patch('/me', input),
};

export const roomApi = {
  graph: (gameId: string, asCharacter: string | null): Promise<GameGraph> =>
    api.get(`/games/${gameId}/graph${query({ asCharacter })}`),
  characters: (gameId: string): Promise<Character[]> => api.get(`/games/${gameId}/characters`),
  channels: (gameId: string): Promise<Channel[]> => api.get(`/games/${gameId}/channels`),
  memberships: (gameId: string): Promise<Membership[]> => api.get(`/games/${gameId}/memberships`),
  unread: (gameId: string, asCharacter: string | null): Promise<Unread> =>
    api.get(`/games/${gameId}/unread${query({ asCharacter })}`),
  feed: (gameId: string, chatId: string, asCharacter: string | null, afterSeq = 0): Promise<Feed> =>
    api.get(`/games/${gameId}/chats/${chatId}/messages${query({ asCharacter, afterSeq, limit: 200 })}`),
  enter: (gameId: string, chatId: string, asCharacter: string | null): Promise<ChatEntry> =>
    api.post(`/games/${gameId}/chats/${chatId}/enter${query({ asCharacter })}`),
  markRead: (gameId: string, chatId: string, asCharacter: string | null, seq?: number): Promise<unknown> =>
    api.post(`/games/${gameId}/chats/${chatId}/read${query({ asCharacter })}`, seq === undefined ? {} : { seq }),
  post: (gameId: string, chatId: string, input: PostMessageInput): Promise<FeedItem> =>
    api.post(`/games/${gameId}/chats/${chatId}/messages`, input),
  move: (gameId: string, characterId: string, chatId: string): Promise<Placement> =>
    api.put(`/games/${gameId}/characters/${characterId}/location`, { chatId }),
  stealth: (gameId: string, characterId: string, hidden: boolean, reason: string | null): Promise<Placement> =>
    api.put(`/games/${gameId}/characters/${characterId}/stealth`, { hidden, reason }),
  restrictions: (gameId: string): Promise<Restriction[]> => api.get(`/games/${gameId}/restrictions`),
  setRestriction: (
    gameId: string,
    input: {
      subjectKind: 'CHARACTER' | 'MEMBERSHIP';
      subjectId: string;
      kind: RestrictionKind;
      reason: string;
      expiresAt?: string | null;
    },
  ): Promise<Restriction> => api.post(`/games/${gameId}/restrictions`, input),
  revokeRestriction: (gameId: string, restrictionId: string): Promise<Restriction> =>
    api.post(`/games/${gameId}/restrictions/${restrictionId}/revoke`),
  turn: (gameId: string): Promise<Turn> => api.get(`/games/${gameId}/turn`),
  openTurn: (gameId: string): Promise<Turn> => api.post(`/games/${gameId}/turn/open`),
  closeTurn: (gameId: string): Promise<Turn> => api.post(`/games/${gameId}/turn/close`),
};

export type { Channel, ChannelMember };
