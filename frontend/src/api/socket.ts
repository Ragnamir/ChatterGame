import type { ChatKind, FeedItem, MessageKind } from '../api/types';

export interface SocketChatEvent {
  chatId: string;
  item: FeedItem;
}

export interface SocketMetaEvent {
  chatId: string;
  seq: number;
  kind: MessageKind;
  eventType: string;
  unread: number;
}

export interface SocketHandlers {
  onReady?: () => void;
  onMeta?: (event: SocketMetaEvent) => void;
  onChat?: (event: SocketChatEvent) => void;
  onSubscription?: (chatId: string, status: string, reason: string | null) => void;
  onResumed?: (lastSeq: number, delivered: number) => void;
  onError?: (code: string, message: string) => void;
  onDisconnected?: () => void;
  onSubscribed?: (chatId: string, chatKind: ChatKind, subjectId: string) => void;
}

/**
 * Сокет без STOMP (`REQUIREMENTS §21`): handshake идёт по cookie, поэтому URL строится
 * от текущего origin, а не от адреса API. Права проверяются на каждое событие сервером
 * (`D-065`); клиент лишь повторяет подписки после переподключения, потому что они живут
 * в памяти соединения.
 */
export class GameSocket {
  private socket: WebSocket | null = null;
  private readonly handlers: SocketHandlers;
  private readonly gameId: string;
  private readonly subscriptions = new Map<string, string | null>();
  private readonly subscriptionKinds = new Map<string, ChatKind>();
  private closedByUser = false;
  private lastSeq = 0;
  private retry = 0;
  private retryTimer: number | null = null;

  constructor(gameId: string, handlers: SocketHandlers) {
    this.gameId = gameId;
    this.handlers = handlers;
  }

  connect(): void {
    this.closedByUser = false;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${protocol}//${window.location.host}/games/${this.gameId}/ws`);
    this.socket = socket;

    socket.addEventListener('open', () => {
      this.retry = 0;
      for (const [chatId, asCharacter] of this.subscriptions) {
        this.send({ type: 'subscribe', chatId, asCharacter: asCharacter ?? undefined });
      }
      if (this.lastSeq > 0) {
        this.send({ type: 'resume', lastSeq: this.lastSeq });
      }
    });

    socket.addEventListener('message', (message) => {
      this.handle(message.data);
    });

    socket.addEventListener('close', () => {
      this.socket = null;
      this.handlers.onDisconnected?.();
      if (!this.closedByUser) {
        this.scheduleReconnect();
      }
    });

    socket.addEventListener('error', () => {
      socket.close();
    });
  }

  close(): void {
    this.closedByUser = true;
    if (this.retryTimer !== null) {
      window.clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.socket?.close();
    this.socket = null;
  }

  /** Подписка на чат; `null` — смотреть участием (наблюдатель, `D-086`). */
  subscribe(chatId: string, asCharacter: string | null): void {
    this.subscriptions.set(chatId, asCharacter);
    if (this.isOpen()) {
      this.send({ type: 'subscribe', chatId, asCharacter: asCharacter ?? undefined });
    }
  }

  unsubscribe(chatId: string): void {
    this.subscriptions.delete(chatId);
    if (this.isOpen()) {
      this.send({ type: 'unsubscribe', chatId });
    }
  }

  post(chatId: string, asCharacter: string | null, kind: MessageKind, body: string | null): boolean {
    if (!this.isOpen()) {
      return false;
    }
    return this.send({ type: 'post', chatId, asCharacter: asCharacter ?? undefined, kind, body });
  }

  markDelivered(seq: number): void {
    if (seq > this.lastSeq) {
      this.lastSeq = seq;
    }
  }

  isOpen(): boolean {
    return this.socket !== null && this.socket.readyState === WebSocket.OPEN;
  }

  private scheduleReconnect(): void {
    this.retry += 1;
    const delay = Math.min(30_000, 500 * 2 ** Math.min(this.retry, 6));
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null;
      if (!this.closedByUser) {
        this.connect();
      }
    }, delay);
  }

  private send(payload: Record<string, unknown>): boolean {
    if (!this.isOpen()) {
      return false;
    }
    this.socket?.send(JSON.stringify(payload));
    return true;
  }

  private handle(raw: string): void {
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      return;
    }
    const channel = String(event.channel ?? '');
    if (channel === 'READY') {
      this.handlers.onReady?.();
      return;
    }
    if (channel === 'GAME') {
      this.markDelivered(Number(event.seq ?? 0));
      this.handlers.onMeta?.({
        chatId: String(event.chatId),
        seq: Number(event.seq ?? 0),
        kind: String(event.kind) as MessageKind,
        eventType: String(event.eventType ?? ''),
        unread: Number(event.unread ?? 0),
      });
      return;
    }
    if (channel === 'CHAT') {
      const item: FeedItem = {
        seq: Number(event.seq ?? 0),
        kind: String(event.kind) as MessageKind,
        messageId: (event.messageId as string | null) ?? null,
        chatId: String(event.chatId),
        characterId: (event.characterId as string | null) ?? null,
        authorMembershipId: (event.authorMembershipId as string | null) ?? null,
        authorUserId: (event.authorUserId as string | null) ?? null,
        onBehalfOfMaster: Boolean(event.onBehalfOfMaster),
        hiddenAuthor: Boolean(event.hiddenAuthor),
        body: (event.body as string | null) ?? null,
        systemCode: (event.systemCode as string | null) ?? null,
        payload: (event.payload as Record<string, unknown> | null) ?? null,
        createdAt: String(event.createdAt ?? new Date().toISOString()),
        matchedNoteIds: Array.isArray(event.matchedNoteIds) ? (event.matchedNoteIds as string[]) : [],
      };
      this.markDelivered(item.seq);
      this.handlers.onChat?.({ chatId: item.chatId, item });
      return;
    }
    if (channel === 'SUBSCRIBED') {
      const chatKind = String(event.chatKind) as ChatKind;
      this.subscriptionKinds.set(String(event.chatId), chatKind);
      this.handlers.onSubscribed?.(String(event.chatId), chatKind, String(event.subjectId ?? ''));
      return;
    }
    if (channel === 'SUBSCRIPTION') {
      this.handlers.onSubscription?.(
        String(event.chatId),
        String(event.status ?? ''),
        (event.reason as string | null) ?? null,
      );
      return;
    }
    if (channel === 'RESUMED') {
      this.handlers.onResumed?.(Number(event.lastSeq ?? 0), Number(event.delivered ?? 0));
      return;
    }
    if (channel === 'ERROR') {
      this.handlers.onError?.(String(event.code ?? 'unknown'), String(event.message ?? ''));
    }
  }
}
