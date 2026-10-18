import type {
  Channel,
  Character,
  ChatKind,
  FeedItem,
  GraphEdge,
  GraphNode,
  PresentCharacter,
} from '../api/types';

export interface ChatRailItem {
  id: string;
  title: string;
  kind: ChatKind;
  unread: number;
  archived: boolean;
  disabled: boolean;
  reason: string | null;
}

/**
 * Список чатов: локации графа и закрытые каналы. Скрытые персонажи и чужие личные
 * каналы уже отсеяны сервером (`D-030`), поэтому клиент не вычитает их повторно —
 * второй фильтр на чтении разошёлся бы с бейджем.
 */
export function buildChatRail(
  nodes: readonly GraphNode[],
  channels: readonly Channel[],
  unread: ReadonlyMap<string, number>,
): ChatRailItem[] {
  const locations = nodes.map((node) => ({
    id: node.id,
    title: node.title,
    kind: 'LOCATION' as const,
    unread: unread.get(node.id) ?? 0,
    archived: node.archived,
    disabled: false,
    reason: node.archivedReason,
  }));
  const closed = channels.map((channel) => ({
    id: channel.id,
    title: channel.title,
    kind: channel.kind,
    unread: unread.get(channel.id) ?? channel.unread,
    archived: channel.archived,
    disabled: channel.disabled,
    reason: channel.archivedReason,
  }));
  return [...locations, ...closed];
}

export function unreadMap(chats: readonly { chatId: string; count: number }[]): Map<string, number> {
  return new Map(chats.map((entry) => [entry.chatId, entry.count]));
}

/**
 * Кем можно говорить в открытом чате. У локации это присутствующие персонажи, у канала —
 * его состав-персонажи. Скрытые уже не приходят игроку вовсе; мастер видит их отдельно
 * и не должен выбирать их за игрока, поэтому фильтр по `hidden` остаётся.
 */
export function speakersFor(
  chatId: string,
  nodes: readonly GraphNode[],
  channels: readonly Channel[],
  characters: readonly Character[],
): PresentCharacter[] {
  const node = nodes.find((entry) => entry.id === chatId);
  if (node) {
    return node.present.filter((character) => !character.hidden);
  }
  const channel = channels.find((entry) => entry.id === chatId);
  if (!channel) {
    return [];
  }
  const allowed = new Set(
    channel.members
      .filter((member) => member.subjectKind === 'CHARACTER' && member.state === 'JOINED')
      .map((member) => member.subjectId),
  );
  return characters
    .filter((character) => allowed.has(character.id) && !character.hidden)
    .map((character) => ({
      id: character.id,
      name: character.name,
      portraitAssetId: character.portraitAssetId,
      hidden: character.hidden,
    }));
}

/**
 * `PLAN F02`: выбор персонажа обязателен при двух присутствующих и отсутствует при одном.
 * Возвращает `null`, когда выбор нужен, но ещё не сделан, и идентификатор — когда
 * отправлять можно. Ноль присутствующих тоже даёт `null`: говорить некому.
 */
export function resolveSpeaker(
  speakers: readonly PresentCharacter[],
  selectedId: string | null,
): string | null {
  if (speakers.length === 1) {
    return speakers[0]?.id ?? null;
  }
  if (speakers.length === 0 || selectedId === null) {
    return null;
  }
  return speakers.some((speaker) => speaker.id === selectedId) ? selectedId : null;
}

/** Единственного присутствующего подставляем сразу; при двух и более выбор остаётся за игроком. */
export function defaultSpeakerId(speakers: readonly PresentCharacter[]): string | null {
  return speakers.length === 1 ? speakers[0]?.id ?? null : null;
}

export type EdgeVisualState = 'AVAILABLE' | 'CLOSED';

export interface VisibleTransition {
  id: string;
  fromChatId: string;
  toChatId: string;
  title: string | null;
  state: EdgeVisualState;
  reason: string | null;
}

/**
 * `UNKNOWN` в ответ не попадает по контракту, но проверка остаётся: ребро, пришедшее
 * как неизвестное, не должно появиться на карте даже при ошибке сервера.
 * `state === null` — взгляд мастера, для него ребро доступно.
 */
export function visibleTransitions(edges: readonly GraphEdge[]): VisibleTransition[] {
  return edges
    .filter((edge) => edge.state !== 'UNKNOWN' && !edge.archived)
    .map((edge) => ({
      id: edge.id,
      fromChatId: edge.fromChatId,
      toChatId: edge.toChatId,
      title: edge.title,
      state: edge.state === 'KNOWN_UNAVAILABLE' ? 'CLOSED' : 'AVAILABLE',
      reason: edge.state === 'KNOWN_UNAVAILABLE' ? edge.reason : null,
    }));
}

/** Слияние страниц и событий: `seq` — единственный ключ порядка (`D-033`). */
export function mergeFeed(existing: readonly FeedItem[], incoming: readonly FeedItem[]): FeedItem[] {
  const bySeq = new Map<number, FeedItem>();
  for (const item of existing) {
    bySeq.set(item.seq, item);
  }
  for (const item of incoming) {
    bySeq.set(item.seq, item);
  }
  return [...bySeq.values()].sort((left, right) => left.seq - right.seq);
}

/** Догрузка идёт вперёд по `afterSeq`; последняя метка и есть запрос следующей страницы. */
export function nextAfterSeq(items: readonly FeedItem[], fallback: number): number {
  if (items.length === 0) {
    return fallback;
  }
  return Math.max(...items.map((item) => item.seq));
}
