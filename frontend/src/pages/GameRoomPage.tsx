import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { GameSocket } from '../api/socket';
import { gamesApi, llmApi, notebookApi, requestApi, roomApi } from '../api/endpoints';
import type {
  Channel,
  Character,
  ChatKind,
  FeedItem,
  Game,
  GameGraph,
  GameRequest,
  LlmStatus,
  Membership,
  MessageKind,
  Note,
  Restriction,
  RestrictionKind,
  SavedMessage,
  Turn,
  Unread,
} from '../api/types';
import { ChannelActions } from '../components/ChannelActions';
import { Composer } from '../components/Composer';
import { LlmPanel } from '../components/LlmPanel';
import { Notebook, type NoteTarget } from '../components/Notebook';
import { MasterPanel } from '../components/MasterPanel';
import { Notice, describeError } from '../components/Notice';
import { RequestPanel } from '../components/RequestPanel';
import { RestrictionNotice } from '../components/RestrictionNotice';
import { GameHeader, TurnPanel } from '../components/TurnPanel';
import { TransitionMap } from '../components/TransitionMap';
import { VirtualFeed } from '../components/VirtualFeed';
import { translate, type MessageKey } from '../i18n/ru';
import { loadFeed, saveFeed } from '../lib/feedCache';
import { ReconnectClock } from '../lib/reconnect';
import {
  buildChatRail,
  defaultSpeakerId,
  mergeFeed,
  nextAfterSeq,
  resolveSpeaker,
  speakersFor,
  unreadMap,
  visibleTransitions,
} from '../lib/room';
import {
  canWriteNotes,
  noteTitles as buildNoteTitles,
  notebookScope,
  notebookTabs,
} from '../lib/notebook';
import { blockedKindReasons } from '../lib/turn';
import { useSession } from '../session/SessionProvider';

const CHAT_KIND_LABEL: Record<ChatKind, MessageKey> = {
  LOCATION: 'chat.kind.LOCATION',
  RADIO: 'chat.kind.RADIO',
  MASTER_DM: 'chat.kind.MASTER_DM',
  WHISPER: 'chat.kind.WHISPER',
  FINAL: 'chat.kind.FINAL',
};

type RoomData = {
  game: Game;
  graph: GameGraph;
  channels: Channel[];
  characters: Character[];
  memberships: Membership[];
  unread: Unread;
  turn: Turn;
  restrictions: Restriction[];
  notes: Note[];
  saved: SavedMessage[];
};

const EMPTY_GRAPH: GameGraph = { nodes: [], edges: [] };

const EMPTY_UNREAD: Unread = { total: 0, chats: [] };

export function GameRoomPage() {
  const { gameId = '' } = useParams();
  const { user } = useSession();
  const [data, setData] = useState<RoomData | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [activeChatId, setActiveChatId] = useState<string | null>(null);
  const [feed, setFeed] = useState<FeedItem[]>([]);
  const [feedKind, setFeedKind] = useState<ChatKind>('LOCATION');
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [selectedSpeaker, setSelectedSpeaker] = useState<string | null>(null);
  const [viewAs, setViewAs] = useState<string | null>(null);
  const [offline, setOffline] = useState(() => !navigator.onLine);
  const [socketState, setSocketState] = useState<'connecting' | 'open' | 'closed'>('connecting');
  const [reconnectMs, setReconnectMs] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [requests, setRequests] = useState<GameRequest[]>([]);
  const [llmStatus, setLlmStatus] = useState<LlmStatus | null>(null);

  const socketRef = useRef<GameSocket | null>(null);
  const clock = useRef(new ReconnectClock());
  const activeChatRef = useRef<string | null>(null);

  const membership = useMemo(
    () => data?.memberships.find((entry) => entry.userId === user?.id) ?? null,
    [data, user],
  );
  const isMaster = membership?.role === 'MASTER';
  const isObserver = membership?.role === 'OBSERVER';
  const ownCharacterId = useMemo(
    () => data?.characters.find((character) => character.mine && !character.archived)?.id ?? null,
    [data],
  );

  // Чтение требует субъекта (`D-086`): игрок смотрит своим персонажем, наблюдатель —
  // участием, мастер API обязан назвать персонажа, поэтому выбирает, чьими глазами читать.
  // Пока мастер не выбрал, берётся первый живой персонаж: иначе первый же кадр ушёл бы
  // в API и сокет без субъекта и получил `character_required`.
  const masterViewId = useMemo(
    () => viewAs ?? data?.characters.find((character) => !character.archived)?.id ?? null,
    [viewAs, data],
  );
  const readAs = isObserver ? null : isMaster ? masterViewId : ownCharacterId;
  const graphAs = isMaster || isObserver ? null : ownCharacterId;
  const canRead = isObserver || readAs !== null;

  const load = useCallback(async () => {
    if (!gameId) {
      return;
    }
    try {
      const [game, channels, characters, memberships, turn, restrictions, notes, saved] =
        await Promise.all([
          gamesApi.read(gameId),
          roomApi.channels(gameId),
          roomApi.characters(gameId),
          roomApi.memberships(gameId),
          roomApi.turn(gameId),
          roomApi.restrictions(gameId),
          notebookApi.notes(gameId),
          notebookApi.saved(gameId),
        ]);
      setData({
        game,
        graph: EMPTY_GRAPH,
        channels,
        characters,
        memberships,
        unread: EMPTY_UNREAD,
        turn,
        restrictions,
        notes,
        saved,
      });
      setFailure(null);
    } catch (error) {
      setFailure(describeError(error));
    }
  }, [gameId]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * Очередь заявок и доступность модели — только мастеру (`REQUIREMENTS §17`): игроку
   * собственные заявки показывает другой экран, а очередь мастера — не публичная лента.
   * Ошибка загрузки не валит комнату: комната живёт и без панели заявок.
   */
  const loadRequests = useCallback(async () => {
    if (!gameId || !isMaster) {
      return;
    }
    try {
      const [queue, status] = await Promise.all([
        requestApi.list(gameId),
        llmApi.status(gameId),
      ]);
      setRequests(queue);
      setLlmStatus(status);
    } catch {
      setRequests([]);
      setLlmStatus(null);
    }
  }, [gameId, isMaster]);

  useEffect(() => {
    void loadRequests();
  }, [loadRequests]);

  useEffect(() => {
    if (!data) {
      return;
    }
    if (!isMaster && !isObserver && ownCharacterId === null) {
      return;
    }
    let alive = true;
    roomApi
      .graph(gameId, graphAs)
      .then((graph) => {
        if (alive) {
          setData((current) => (current ? { ...current, graph } : current));
        }
      })
      .catch((error: unknown) => {
        if (alive) {
          setFailure(describeError(error));
        }
      });
    return () => {
      alive = false;
    };
  }, [gameId, graphAs, isMaster, isObserver, ownCharacterId, data === null]);

  useEffect(() => {
    if (!data || !canRead) {
      return;
    }
    let alive = true;
    roomApi
      .unread(gameId, readAs)
      .then((unread) => {
        if (alive) {
          setData((current) => (current ? { ...current, unread } : current));
        }
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [gameId, readAs, canRead, data === null]);

  useEffect(() => {
    if (!data) {
      return;
    }
    const mine = data.characters.filter((character) => character.mine && !character.archived);
    const preferred =
      data.graph.nodes.find((node) =>
        node.present.some((present) => mine.some((character) => character.id === present.id)),
      )?.id ?? data.graph.nodes[0]?.id ?? null;
    setActiveChatId((current) => current ?? preferred);
  }, [data]);

  useEffect(() => {
    activeChatRef.current = activeChatId;
  }, [activeChatId]);

  const rail = useMemo(
    () => (data ? buildChatRail(data.graph.nodes, data.channels, unreadMap(data.unread.chats)) : []),
    [data],
  );

  const speakers = useMemo(
    () =>
      data && activeChatId
        ? speakersFor(activeChatId, data.graph.nodes, data.channels, data.characters)
        : [],
    [data, activeChatId],
  );

  useEffect(() => {
    setSelectedSpeaker(isMaster ? null : defaultSpeakerId(speakers));
  }, [speakers, isMaster]);

  const speakerId = resolveSpeaker(speakers, selectedSpeaker);

  const names = useMemo(
    () => new Map((data?.characters ?? []).map((character) => [character.id, character.name])),
    [data],
  );

  // Тетрадь читается по игре и участию, а не по говорящему: смена персонажа не должна
  // перезагружать заметки и терять открытую вкладку (`PLAN F04`).
  const noteById = useMemo(
    () => new Map((data?.notes ?? []).map((note) => [note.id, note])),
    [data],
  );
  const noteTitleMap = useMemo(
    () => buildNoteTitles(data?.notes ?? [], names),
    [data, names],
  );
  const noteIds = useMemo(() => new Set(noteById.keys()), [noteById]);
  const savedMessageIds = useMemo(
    () => new Set((data?.saved ?? []).map((entry) => entry.messageId)),
    [data],
  );
  const noteTargets = useMemo(() => {
    const live = (data?.characters ?? []).filter(
      (character) => !character.archived && (isMaster || character.mine),
    );
    const targets: NoteTarget[] = live.map((character) => ({
      id: character.id,
      label: character.name,
      kind: 'CHARACTER',
    }));
    if (isMaster && membership) {
      targets.push({
        id: membership.id,
        label: translate('notebook.selfTarget'),
        kind: 'MEMBERSHIP' as const,
      });
    }
    return targets;
  }, [data, isMaster, membership]);

  useEffect(() => {
    if (!gameId || !activeChatId || !data || !canRead) {
      return;
    }
    let alive = true;
    const chatId = activeChatId;
    setFeed([]);
    setHasMore(false);
    roomApi
      .enter(gameId, chatId, readAs)
      .then((entry) => {
        if (!alive) {
          return;
        }
        setFeedKind(entry.chatKind);
        const seeded = entry.lastMessage ? [entry.lastMessage] : [];
        setFeed(seeded);
        if (seeded.length > 0) {
          socketRef.current?.markDelivered(entry.cursor);
        }
        void roomApi.markRead(gameId, chatId, readAs, entry.cursor).catch(() => undefined);
      })
      .catch((error: unknown) => {
        if (!alive) {
          return;
        }
        const cached = user ? loadFeed(window.localStorage, user.id, gameId, chatId, readAs) : null;
        if (cached) {
          setFeed(cached.items);
          setNotice(translate('room.offlineFeed'));
          return;
        }
        setFailure(describeError(error));
      });
    return () => {
      alive = false;
    };
  }, [gameId, activeChatId, readAs, canRead, data === null, user]);

  // Сокет: подписка на открытый чат и приём мета- и чат-событий. Смена персонажа
  // пересоздаёт подписку: права и субъект подписки связаны (`D-065`, `D-086`).
  useEffect(() => {
    if (!gameId || data === null || !canRead) {
      return;
    }
    const socket = new GameSocket(gameId, {
      onReady: () => {
        setSocketState('open');
      },
      onDisconnected: () => {
        setSocketState('closed');
        clock.current.markDisconnected(Date.now());
      },
      onChat: ({ chatId, item }) => {
        setReconnectMs(clock.current.markFirstMessage(Date.now())?.elapsedMs ?? null);
        if (chatId !== activeChatRef.current) {
          return;
        }
        setFeed((current) => mergeFeed(current, [item]));
      },
      onMeta: () => {
        void load();
      },
      onSubscription: (_chatId, status) => {
        if (status === 'REVOKED') {
          setNotice(translate('room.accessRevoked'));
        }
      },
      onError: (code) => {
        setNotice(translate('room.socketError', { code }));
      },
    });
    socketRef.current = socket;
    socket.connect();
    return () => {
      socket.close();
      socketRef.current = null;
    };
  }, [gameId, data === null, canRead, load]);

  useEffect(() => {
    const socket = socketRef.current;
    if (!socket || !activeChatId || !canRead) {
      return;
    }
    socket.subscribe(activeChatId, readAs);
    return () => {
      socket.unsubscribe(activeChatId);
    };
  }, [activeChatId, readAs, socketState]);

  useEffect(() => {
    const online = () => setOffline(false);
    const offlineListener = () => setOffline(true);
    window.addEventListener('online', online);
    window.addEventListener('offline', offlineListener);
    return () => {
      window.removeEventListener('online', online);
      window.removeEventListener('offline', offlineListener);
    };
  }, []);

  useEffect(() => {
    if (!user || !gameId || !activeChatId || feed.length === 0) {
      return;
    }
    saveFeed(window.localStorage, user.id, gameId, activeChatId, readAs, feed, feed[feed.length - 1]?.seq ?? 0);
  }, [feed, user, gameId, activeChatId, readAs]);

  const loadMore = useCallback(async () => {
    if (!gameId || !activeChatId || loadingMore) {
      return;
    }
    setLoadingMore(true);
    try {
      const page = await roomApi.feed(gameId, activeChatId, readAs, nextAfterSeq(feed, 0));
      setFeed((current) => mergeFeed(current, page.items));
      setHasMore(page.items.length >= 200);
    } catch {
      setHasMore(false);
    } finally {
      setLoadingMore(false);
    }
  }, [gameId, activeChatId, readAs, feed, loadingMore]);

  async function send(kind: MessageKind, body: string): Promise<void> {
    if (!activeChatId) {
      return;
    }
    const speaker = resolveSpeaker(speakers, selectedSpeaker);
    // Мастер без выбранного персонажа пишет событием мира (`D-004`).
    const actualKind: MessageKind = isMaster && speaker === null ? 'MASTER' : kind;
    const sentViaSocket = socketRef.current?.post(activeChatId, speaker, actualKind, body) ?? false;
    if (sentViaSocket) {
      return;
    }
    const item = await roomApi.post(gameId, activeChatId, {
      kind: actualKind,
      characterId: speaker,
      body,
    });
    setFeed((current) => mergeFeed(current, [item]));
  }

  async function refresh(): Promise<void> {
    await load();
  }

  async function move(characterId: string, chatId: string): Promise<void> {
    await roomApi.move(gameId, characterId, chatId);
    await refresh();
  }

  async function stealth(characterId: string, hidden: boolean, reason: string | null): Promise<void> {
    await roomApi.stealth(gameId, characterId, hidden, reason);
    await refresh();
  }

  async function restrict(input: {
    subjectKind: 'CHARACTER' | 'MEMBERSHIP';
    subjectId: string;
    kind: RestrictionKind;
    reason: string;
    expiresAt: string | null;
  }): Promise<void> {
    await roomApi.setRestriction(gameId, input);
    await refresh();
  }

  async function revoke(restrictionId: string): Promise<void> {
    await roomApi.revokeRestriction(gameId, restrictionId);
    await refresh();
  }

  async function decide(requestId: string, approve: number[]): Promise<void> {
    await requestApi.decide(gameId, requestId, approve);
    await loadRequests();
    await refresh();
  }

  async function rollback(requestId: string, approve: number[]): Promise<void> {
    await requestApi.rollback(gameId, requestId, approve);
    await loadRequests();
    await refresh();
  }

  async function propose(input: {
    type: 'CO_MASTER' | 'AUTOPILOT';
    instruction: string;
    chatId: string | null;
    characterId: string | null;
  }): Promise<void> {
    await llmApi.propose(gameId, input);
    // Ответ модели приходит заявкой: очередь обновляется, а мир меняется только после
    // решения мастера (`D-040`), поэтому ленту здесь перечитывать не нужно.
    await loadRequests();
  }

  async function openTurn(): Promise<void> {
    await roomApi.openTurn(gameId);
    await refresh();
  }

  async function closeTurn(): Promise<void> {
    await roomApi.closeTurn(gameId);
    await refresh();
  }

  async function createNote(input: {
    targetKind: 'CHARACTER' | 'MEMBERSHIP';
    targetId: string;
    body: string;
    keywords: { raw: string; exactMatch: boolean }[];
  }): Promise<void> {
    await notebookApi.createNote(gameId, input);
    await refresh();
  }

  async function commentSaved(savedId: string, comment: string): Promise<void> {
    await notebookApi.commentSaved(gameId, savedId, comment.trim().length === 0 ? null : comment);
    await refresh();
  }

  async function saveMessage(item: FeedItem): Promise<void> {
    if (!item.messageId) {
      return;
    }
    await notebookApi.save(gameId, { messageId: item.messageId, comment: null, noteId: null });
    await refresh();
    setNotice(translate('notebook.savedOk'));
  }

  async function createWhisper(characterIds: string[]): Promise<void> {
    const chat = await roomApi.createWhisper(gameId, characterIds);
    await refresh();
    setActiveChatId(chat.id);
  }

  async function whisperDecision(
    chatId: string,
    action: 'accept' | 'decline' | 'close',
  ): Promise<void> {
    if (action === 'accept') {
      await roomApi.acceptWhisper(gameId, chatId);
    } else if (action === 'decline') {
      await roomApi.declineWhisper(gameId, chatId);
    } else {
      await roomApi.closeWhisper(gameId, chatId);
    }
    await refresh();
  }

  async function createRadio(title: string, characterIds: string[]): Promise<void> {
    const chat = await roomApi.createRadio(gameId, title, characterIds);
    await refresh();
    setActiveChatId(chat.id);
  }

  async function radioMembers(chatId: string, characterIds: string[]): Promise<void> {
    await roomApi.setChannelMembers(gameId, chatId, characterIds);
    await refresh();
  }

  async function radioDisabled(chatId: string, disabled: boolean): Promise<void> {
    await roomApi.setChannelDisabled(gameId, chatId, disabled);
    await refresh();
  }

  if (failure && !data) {
    return (
      <>
        <Notice code={failure} />
        <Link className="button button--ghost" to="/games">
          {translate('room.back')}
        </Link>
      </>
    );
  }

  if (!data) {
    return (
      <p className="loading" role="status">
        {translate('app.loading')}
      </p>
    );
  }

  const activeChannel = data.channels.find((channel) => channel.id === activeChatId) ?? null;
  const activeNode = data.graph.nodes.find((node) => node.id === activeChatId) ?? null;
  const archived = Boolean(activeChannel?.archived ?? activeNode?.archived);
  const disabledChat = activeChannel?.disabled ? translate('room.disabled') : null;
  const composerBlock = archived
    ? translate('room.archived')
    : data.game.readOnly
      ? translate('room.readOnly')
      : data.game.paused
        ? translate('room.paused', { reason: data.game.pauseReason ?? translate('map.noReason') })
        : (disabledChat ?? null);
  const blockedKinds = blockedKindReasons(
    { phase: data.game.phase, turn: data.turn, restrictions: data.restrictions },
    feedKind,
    speakerId,
    membership?.id ?? null,
    isMaster,
  );
  const composerKinds: readonly MessageKind[] = isObserver
    ? ['JOKE']
    : isMaster
      ? ['SPEECH', 'ACTION', 'JOKE', 'MASTER']
      : ['SPEECH', 'ACTION', 'JOKE'];

  return (
    <>
      <GameHeader game={data.game} membership={membership} />
      <div className="room__toolbar">
        <Link className="button button--ghost" to="/games">
          {translate('room.back')}
        </Link>
        {isMaster && data.characters.length > 0 ? (
          <label className="room__viewAs">
            <span>{translate('room.viewAs')}</span>
            <select
              aria-label={translate('room.viewAs')}
              value={masterViewId ?? ''}
              onChange={(event) => setViewAs(event.target.value || null)}
            >
              {data.characters
                .filter((character) => !character.archived)
                .map((character) => (
                  <option key={character.id} value={character.id}>
                    {character.name}
                  </option>
                ))}
            </select>
          </label>
        ) : null}
        <span className={`badge${socketState === 'open' ? ' badge--accent' : ''}`}>
          {socketState === 'open'
            ? translate('room.online')
            : canRead
              ? translate('room.offline')
              : translate('room.noSubjectBadge')}
        </span>
        {offline ? <span className="badge">{translate('room.noNetwork')}</span> : null}
        {reconnectMs !== null ? (
          <span className="badge">
            {translate('room.reconnectMetric', { ms: reconnectMs })}
          </span>
        ) : null}
      </div>

      {failure ? <Notice code={failure} /> : null}
      {notice ? (
        <div className="notice notice--ok" role="status">
          <p className="notice__title">{notice}</p>
        </div>
      ) : null}
      {offline ? (
        <div className="notice" role="status">
          <p className="notice__title">{translate('room.offlineBanner')}</p>
        </div>
      ) : null}

      <div className="room">
        <nav className="room__rail" aria-label={translate('room.chats')}>
          <ul
            className="chat-list"
            onKeyDown={(event) => {
              if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
                return;
              }
              event.preventDefault();
              const index = rail.findIndex((item) => item.id === activeChatId);
              const next = event.key === 'ArrowDown' ? index + 1 : index - 1;
              const target = rail[Math.min(Math.max(next, 0), rail.length - 1)];
              if (target) {
                setActiveChatId(target.id);
              }
            }}
          >
            {rail.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  className={`chat-list__item${item.id === activeChatId ? ' chat-list__item--active' : ''}`}
                  aria-current={item.id === activeChatId ? 'true' : undefined}
                  onClick={() => setActiveChatId(item.id)}
                >
                  <span className="chat-list__title">{item.title}</span>
                  <span className="chat-list__meta">
                    <span className="chat-list__kind">{translate(CHAT_KIND_LABEL[item.kind])}</span>
                    {item.unread > 0 ? (
                      <span className="badge badge--accent" aria-label={translate('room.unreadLabel', { count: item.unread })}>
                        {item.unread}
                      </span>
                    ) : null}
                    {item.archived ? <span className="badge">{translate('games.badge.archived')}</span> : null}
                    {item.disabled ? <span className="badge">{translate('room.disabledBadge')}</span> : null}
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <TransitionMap
            nodes={data.graph.nodes}
            transitions={visibleTransitions(data.graph.edges)}
            currentChatId={activeChatId}
            onSelect={setActiveChatId}
          />
        </nav>

        <section className="room__main" aria-label={translate('room.currentChat')}>
          <h2 className="room__chatTitle">
            {activeNode?.title ?? activeChannel?.title ?? translate('room.noChat')}
          </h2>
          <p className="room__chatKind">{translate(CHAT_KIND_LABEL[feedKind])}</p>
          {activeNode?.present.length ? (
            <p className="room__present">
              {translate('room.present')}: {activeNode.present.map((character) => character.name).join(', ')}
            </p>
          ) : null}
          {activeChannel?.archivedReason ? (
            <p className="panel__hint">
              {translate('room.archivedReason', { reason: activeChannel.archivedReason })}
            </p>
          ) : null}
          {activeNode?.archivedReason ? (
            <p className="panel__hint">
              {translate('room.archivedReason', { reason: activeNode.archivedReason })}
            </p>
          ) : null}

          {!canRead ? (
            <div className="notice" role="status">
              <p className="notice__title">
                {isMaster && data.characters.length === 0
                  ? translate('room.noCharacters')
                  : translate('room.noPlayerCharacter')}
              </p>
            </div>
          ) : (
            <>
              <VirtualFeed
                items={feed}
                names={names}
                noteTitles={noteTitleMap}
                noteById={noteById}
                highlightNoteIds={noteIds}
                savedMessageIds={savedMessageIds}
                onSave={(item) => void saveMessage(item)}
                loadingOlder={loadingMore}
                canLoadMore={hasMore}
                onLoadMore={() => void loadMore()}
              />

              {activeChatId ? (
                <Composer
                  speakers={speakers}
                  selectedSpeakerId={selectedSpeaker}
                  onSelectSpeaker={setSelectedSpeaker}
                  onSend={send}
                  disabledReason={composerBlock}
                  blockedKinds={blockedKinds}
                  kinds={composerKinds}
                  allowNoSpeaker={isMaster}
                />
              ) : (
                <p className="panel__hint">{translate('room.noChat')}</p>
              )}
            </>
          )}
        </section>
      </div>

      <ChannelActions
        isMaster={isMaster}
        isObserver={isObserver}
        activeChannel={activeChannel}
        candidates={(activeNode?.present ?? [])
          .map((present) => data.characters.find((character) => character.id === present.id))
          .filter((character): character is Character => Boolean(character))}
        characters={data.characters}
        ownCharacterId={ownCharacterId}
        onCreateWhisper={createWhisper}
        onWhisperDecision={whisperDecision}
        onCreateRadio={createRadio}
        onRadioMembers={radioMembers}
        onRadioDisabled={radioDisabled}
      />

      <TurnPanel
        turn={data.turn}
        characterNames={names}
        canControl={isMaster}
        onOpen={openTurn}
        onClose={closeTurn}
      />

      {!isMaster ? (
        <RestrictionNotice
          restrictions={data.restrictions}
          speakerId={speakerId}
          membershipId={membership?.id ?? null}
        />
      ) : null}

      <Notebook
        key={notebookScope(gameId, membership?.id ?? null)}
        tabs={notebookTabs(membership?.role ?? null)}
        notes={data.notes}
        saved={data.saved}
        names={names}
        noteById={noteById}
        targets={noteTargets}
        canWriteNotes={canWriteNotes(membership?.role ?? null)}
        onCreateNote={createNote}
        onComment={commentSaved}
      />

      {isMaster ? (
        <>
          <RequestPanel requests={requests} onDecide={decide} onRollback={rollback} />
          <LlmPanel
            status={llmStatus}
            characters={data.characters}
            channels={data.channels}
            onPropose={propose}
          />
          <MasterPanel
            characters={data.characters}
            nodes={data.graph.nodes}
            memberships={data.memberships}
            restrictions={data.restrictions}
            onMove={move}
            onStealth={stealth}
            onRestrict={restrict}
            onRevoke={revoke}
          />
        </>
      ) : null}
    </>
  );
}
