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
  GameRequest,
  LlmStatus,
  LoginInput,
  Membership,
  Note,
  Placement,
  PostMessageInput,
  RegisterInput,
  RequestStatus,
  RequestType,
  Restriction,
  RestrictionKind,
  SavedMessage,
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

  createRadio: (gameId: string, title: string, characterIds: string[]): Promise<Channel> =>
    api.post(`/games/${gameId}/radios`, { title, characterIds }),
  setChannelMembers: (gameId: string, chatId: string, characterIds: string[]): Promise<Channel> =>
    api.put(`/games/${gameId}/channels/${chatId}/members`, { characterIds }),
  setChannelDisabled: (gameId: string, chatId: string, disabled: boolean): Promise<Channel> =>
    api.put(`/games/${gameId}/channels/${chatId}/disabled`, { disabled }),
  createWhisper: (gameId: string, characterIds: string[]): Promise<Channel> =>
    api.post(`/games/${gameId}/whispers`, { characterIds }),
  acceptWhisper: (gameId: string, chatId: string): Promise<Channel> =>
    api.post(`/games/${gameId}/channels/${chatId}/accept`),
  declineWhisper: (gameId: string, chatId: string): Promise<Channel> =>
    api.post(`/games/${gameId}/channels/${chatId}/decline`),
  closeWhisper: (gameId: string, chatId: string): Promise<Channel> =>
    api.post(`/games/${gameId}/channels/${chatId}/close`),
};

export const notebookApi = {
  notes: (gameId: string): Promise<Note[]> => api.get(`/games/${gameId}/notes`),
  createNote: (
    gameId: string,
    input: {
      targetKind: 'CHARACTER' | 'MEMBERSHIP';
      targetId: string;
      body: string;
      keywords: { raw: string; exactMatch: boolean }[];
    },
  ): Promise<Note> => api.post(`/games/${gameId}/notes`, input),
  saved: (gameId: string): Promise<SavedMessage[]> => api.get(`/games/${gameId}/saved-messages`),
  save: (
    gameId: string,
    input: { messageId: string; comment: string | null; noteId: string | null },
  ): Promise<SavedMessage> => api.post(`/games/${gameId}/saved-messages`, input),
  commentSaved: (gameId: string, savedId: string, comment: string | null): Promise<SavedMessage> =>
    api.put(`/games/${gameId}/saved-messages/${savedId}`, { comment }),
};

export type { Channel, ChannelMember };

/**
 * Заявки и модель (`REQUIREMENTS §17`, §18). Решение передаёт номера операций, а не
 * «одобрить всё»: частичное одобрение — штатный исход (`D-041`), и клиент обязан уметь
 * назвать ровно применённые операции.
 */
export const requestApi = {
  list: (gameId: string, status?: RequestStatus): Promise<GameRequest[]> =>
    api.get(`/games/${gameId}/requests${query({ status })}`),
  read: (gameId: string, requestId: string): Promise<GameRequest> =>
    api.get(`/games/${gameId}/requests/${requestId}`),
  submit: (
    gameId: string,
    input: {
      type: RequestType;
      payload: Record<string, unknown>;
      toCharacterId?: string | null;
      idempotencyKey?: string | null;
    },
  ): Promise<GameRequest> => api.post(`/games/${gameId}/requests`, input),
  decide: (gameId: string, requestId: string, approve: number[]): Promise<GameRequest> =>
    api.post(`/games/${gameId}/requests/${requestId}/decision`, { approve }),
  rollback: (gameId: string, requestId: string, approve: number[]): Promise<GameRequest> =>
    api.post(`/games/${gameId}/requests/${requestId}/rollback`, { approve }),
};

export const llmApi = {
  status: (gameId: string): Promise<LlmStatus> => api.get(`/games/${gameId}/llm`),
  propose: (
    gameId: string,
    input: {
      type: 'CO_MASTER' | 'AUTOPILOT';
      instruction: string;
      chatId?: string | null;
      characterId?: string | null;
      idempotencyKey?: string | null;
    },
  ): Promise<GameRequest> => api.post(`/games/${gameId}/llm/proposals`, input),
};
