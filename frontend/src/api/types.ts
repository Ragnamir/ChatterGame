export type Readiness = 'DRAFT' | 'READY';
export type Visibility = 'PRIVATE' | 'PUBLIC';
export type Phase = 'NOT_STARTED' | 'RUNNING' | 'FINISHED';
export type ThemePreference = 'system' | 'light' | 'dark';

export interface UserProfile {
  id: string;
  login: string;
  displayName: string;
  email: string | null;
  emailVerified: boolean;
  avatarAssetId: string | null;
  theme: ThemePreference;
  createdAt?: string;
}

export interface Game {
  id: string;
  masterUserId: string;
  title: string;
  publicDescription: string | null;
  privateDescription: string | null;
  readiness: Readiness;
  visibility: Visibility;
  phase: Phase;
  paused: boolean;
  pauseReason: string | null;
  turnBased: boolean;
  archived: boolean;
  archivedReason: string | null;
  readOnly: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RegisterInput {
  login: string;
  password: string;
  displayName?: string | null;
  email?: string | null;
}

export interface LoginInput {
  login: string;
  password: string;
}

export interface UpdateProfileInput {
  displayName?: string | null;
  email?: string | null;
  theme?: ThemePreference | null;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
}

export type ChatKind = 'LOCATION' | 'RADIO' | 'MASTER_DM' | 'WHISPER' | 'FINAL';
export type MessageKind = 'SPEECH' | 'ACTION' | 'JOKE' | 'MASTER' | 'SYSTEM';
export type EdgeState = 'UNKNOWN' | 'KNOWN_UNAVAILABLE' | 'AVAILABLE';
export type RestrictionKind = 'NO_SPEECH' | 'NO_ACTION' | 'NO_MOVE' | 'NO_JOKE';

export interface PresentCharacter {
  id: string;
  name: string;
  portraitAssetId: string | null;
  hidden: boolean;
}

export interface GraphNode {
  id: string;
  title: string;
  description: string | null;
  archived: boolean;
  archivedReason: string | null;
  present: PresentCharacter[];
}

export interface GraphEdge {
  id: string;
  fromChatId: string;
  toChatId: string;
  title: string | null;
  archived: boolean;
  archivedReason: string | null;
  state: EdgeState | null;
  reason: string | null;
}

export interface GameGraph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface Character {
  id: string;
  gameId: string;
  name: string;
  portraitAssetId: string | null;
  appearance: string | null;
  publicHistory: string | null;
  privateHistory: string | null;
  playable: boolean;
  npc: boolean;
  archived: boolean;
  archivedReason: string | null;
  hidden: boolean;
  mine: boolean;
}

export interface ChannelMember {
  subjectKind: 'CHARACTER' | 'MEMBERSHIP';
  subjectId: string;
  state: 'INVITED' | 'JOINED' | 'DECLINED' | 'LEFT';
}

export interface Channel {
  id: string;
  gameId: string;
  kind: ChatKind;
  title: string;
  disabled: boolean;
  archived: boolean;
  archivedReason: string | null;
  archivedAt: string | null;
  createdAt: string;
  mySubjectKind: 'CHARACTER' | 'MEMBERSHIP' | null;
  myState: 'INVITED' | 'JOINED' | 'DECLINED' | 'LEFT' | null;
  master: boolean;
  unread: number;
  members: ChannelMember[];
}

export interface FeedItem {
  seq: number;
  kind: MessageKind;
  messageId: string | null;
  chatId: string;
  characterId: string | null;
  authorMembershipId: string | null;
  authorUserId: string | null;
  onBehalfOfMaster: boolean;
  hiddenAuthor: boolean;
  body: string | null;
  systemCode: string | null;
  payload: Record<string, unknown> | null;
  createdAt: string;
  matchedNoteIds: string[];
}

export interface Feed {
  items: FeedItem[];
  lastSeq: number;
  readSeq: number;
  chatKind: ChatKind;
}

export interface Unread {
  total: number;
  chats: { chatId: string; count: number }[];
}

export interface ChatEntry {
  chatId: string;
  chatKind: ChatKind;
  cursor: number;
  lastMessage: FeedItem | null;
}

export interface Restriction {
  id: string;
  gameId: string;
  subjectKind: 'CHARACTER' | 'MEMBERSHIP';
  subjectId: string;
  kind: RestrictionKind;
  reason: string;
  createdBy: string;
  createdAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
}

export interface Membership {
  id: string;
  gameId: string;
  userId: string;
  role: 'MASTER' | 'PLAYER' | 'OBSERVER';
  status: 'INVITED' | 'JOINED' | 'DECLINED' | 'LEFT' | 'BANNED';
  createdAt: string | null;
  joinedAt: string | null;
  leftAt: string | null;
  reservedCharacterId: string | null;
  roleplayWishes: string | null;
}

export interface Turn {
  turnBased: boolean;
  open: boolean;
  n: number;
  openedAt: string | null;
  closedAt: string | null;
  budgets: { characterId: string; actionUsed: boolean; speechUsed: boolean }[];
}

export interface PostMessageInput {
  kind: MessageKind;
  characterId?: string | null;
  body?: string | null;
  onBehalfOfMaster?: boolean;
}

export interface Placement {
  characterId: string;
  chatId: string;
  hidden: boolean;
}

export type NotebookTab = 'notes' | 'saved';

export interface NoteKeyword {
  id?: string;
  raw: string;
  exactMatch: boolean;
}

export interface Note {
  id: string;
  gameId: string;
  authorMembershipId: string;
  targetKind: 'CHARACTER' | 'MEMBERSHIP';
  targetId: string;
  targetLabel: string;
  body: string;
  matchingPending: boolean;
  createdAt: string;
  updatedAt: string;
  keywords: NoteKeyword[];
}

export interface SavedMessage {
  id: string;
  gameId: string;
  ownerMembershipId: string;
  messageId: string;
  textSnapshot: string;
  speakerSnapshot: string | null;
  comment: string | null;
  noteId: string | null;
  createdAt: string;
}
