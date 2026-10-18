import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { GameSocket } from '../api/socket';
import { gamesApi, roomApi } from '../api/endpoints';
import type {
  Channel,
  Character,
  ChatKind,
  FeedItem,
  Game,
  GameGraph,
  Membership,
  MessageKind,
  Restriction,
  RestrictionKind,
  Turn,
  Unread,
} from '../api/types';
import { Composer } from '../components/Composer';
import { MasterPanel } from '../components/MasterPanel';
import { Notice, describeError } from '../components/Notice';
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
      const [game, channels, characters, memberships, turn, restrictions] = await Promise.all([
        gamesApi.read(gameId),
        roomApi.channels(gameId),
        roomApi.characters(gameId),
        roomApi.memberships(gameId),
        roomApi.turn(gameId),
        roomApi.restrictions(gameId),
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
      });
      setFailure(null);
    } catch (error) {
      setFailure(describeError(error));
    }
  }, [gameId]);

  useEffect(() => {
    void load();
  }, [load]);

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
  }): Promise<void> {
    await roomApi.setRestriction(gameId, input);
    await refresh();
  }

  async function revoke(restrictionId: string): Promise<void> {
    await roomApi.revokeRestriction(gameId, restrictionId);
    await refresh();
  }

  async function openTurn(): Promise<void> {
    await roomApi.openTurn(gameId);
    await refresh();
  }

  async function closeTurn(): Promise<void> {
    await roomApi.closeTurn(gameId);
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
  const blockedKinds = blockedKindReasons(data, feedKind, speakerId, membership, isMaster);
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
                noteTitles={new Map()}
                highlightNoteIds={new Set()}
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

      <TurnPanel
        turn={data.turn}
        characterNames={names}
        canControl={isMaster}
        onOpen={openTurn}
        onClose={closeTurn}
      />

      {isMaster ? (
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
      ) : null}
    </>
  );
}

/**
 * Почему тип сообщения закрыт (`PLAN F03`). Причина показывается до попытки отправки:
 * закрытый ход не мешает шутке, а исчерпанный бюджет — второму типу. Радио принимает
 * только речь (`D-039`), поэтому `ACTION` и `JOKE` там закрыты всегда.
 */
function blockedKindReasons(
  data: RoomData,
  kind: ChatKind,
  speakerId: string | null,
  membership: Membership | null,
  isMaster: boolean,
): Map<MessageKind, string> {
  const blocked = new Map<MessageKind, string>();
  if (data.game.phase === 'FINISHED') {
    blocked.set('SPEECH', translate('room.finalJokeOnly'));
    blocked.set('ACTION', translate('room.finalJokeOnly'));
  }
  if (data.turn.turnBased && !data.turn.open) {
    blocked.set('SPEECH', translate('room.turnClosed'));
    blocked.set('ACTION', translate('room.turnClosed'));
  }
  if (kind === 'RADIO') {
    blocked.set('ACTION', translate('composer.radioSpeechOnly'));
    blocked.set('JOKE', translate('composer.radioSpeechOnly'));
  }
  if (kind === 'FINAL' && !isMaster) {
    blocked.set('MASTER', translate('composer.masterOnly'));
  }
  if (speakerId) {
    const budget = data.turn.budgets.find((entry) => entry.characterId === speakerId);
    if (budget?.speechUsed) {
      blocked.set('SPEECH', translate('room.budgetSpeech'));
    }
    if (budget?.actionUsed) {
      blocked.set('ACTION', translate('room.budgetAction'));
    }
  }
  for (const restriction of data.restrictions) {
    if (restriction.revokedAt !== null) {
      continue;
    }
    if (restriction.expiresAt !== null && Date.parse(restriction.expiresAt) <= Date.now()) {
      continue;
    }
    const reason = translate('room.restricted', { reason: restriction.reason });
    if (restriction.kind === 'NO_SPEECH' && restriction.subjectId === speakerId) {
      blocked.set('SPEECH', reason);
    }
    if (restriction.kind === 'NO_ACTION' && restriction.subjectId === speakerId) {
      blocked.set('ACTION', reason);
    }
    if (restriction.kind === 'NO_JOKE' && restriction.subjectId === membership?.id) {
      blocked.set('JOKE', reason);
    }
  }
  return blocked;
}
