import { useEffect, useMemo, useRef, useState } from 'react';
import type { FeedItem } from '../api/types';
import { translate, hasMessageKey, type MessageKey } from '../i18n/ru';
import { isNearBottom, renderWindow } from '../lib/virtual';

const ITEM_HEIGHT = 84;
const OVERSCAN = 8;

const KIND_LABEL: Record<string, MessageKey> = {
  SPEECH: 'feed.kind.SPEECH',
  ACTION: 'feed.kind.ACTION',
  JOKE: 'feed.kind.JOKE',
  MASTER: 'feed.kind.MASTER',
  SYSTEM: 'feed.kind.SYSTEM',
};

function bodyOf(item: FeedItem): string {
  if (item.kind === 'SYSTEM') {
    const code = item.systemCode ? `feed.system.${item.systemCode}` : '';
    return translate(hasMessageKey(code) ? code : 'feed.system.unknown');
  }
  return item.body ?? '';
}

export interface VirtualFeedProps {
  items: readonly FeedItem[];
  names: ReadonlyMap<string, string>;
  noteTitles: ReadonlyMap<string, string>;
  highlightNoteIds: ReadonlySet<string>;
  loadingOlder: boolean;
  canLoadMore: boolean;
  onLoadMore: () => void;
}

/**
 * Лента с окном рендера (`D-082`). Строки одинаковой высоты: это условие виртуализации,
 * и оно же держит скролл 60 fps на 10 000 сообщений. Догрузка продолжает вперёд от
 * последнего `seq` — тот же путь, которым идёт resume (`D-083`), поэтому дублей не даёт.
 */
export function VirtualFeed({
  items,
  names,
  noteTitles,
  highlightNoteIds,
  loadingOlder,
  canLoadMore,
  onLoadMore,
}: VirtualFeedProps) {
  const viewport = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [height, setHeight] = useState(480);
  const pinned = useRef(true);
  const previousCount = useRef(items.length);

  useEffect(() => {
    const element = viewport.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver(() => setHeight(element.clientHeight));
    observer.observe(element);
    setHeight(element.clientHeight);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const element = viewport.current;
    if (element && pinned.current && items.length > previousCount.current) {
      element.scrollTop = element.scrollHeight;
      setScrollTop(element.scrollTop);
    }
    previousCount.current = items.length;
  }, [items.length]);

  const windowed = useMemo(
    () => renderWindow(scrollTop, height, ITEM_HEIGHT, items.length, OVERSCAN),
    [scrollTop, height, items.length],
  );
  const visible = items.slice(windowed.start, windowed.end);

  function onScroll(): void {
    const element = viewport.current;
    if (!element) {
      return;
    }
    setScrollTop(element.scrollTop);
    pinned.current = isNearBottom(element.scrollTop, element.clientHeight, element.scrollHeight);
    if (pinned.current && canLoadMore && !loadingOlder) {
      onLoadMore();
    }
  }

  return (
    <div
      className="feed"
      ref={viewport}
      onScroll={onScroll}
      role="log"
      aria-live="polite"
      aria-relevant="additions"
      aria-label={translate('feed.label')}
      data-rendered-nodes={visible.length}
    >
      <div className="feed__spacer" style={{ height: windowed.totalHeight }} aria-hidden="true" />
      <ol className="feed__list" style={{ transform: `translateY(${windowed.offsetTop}px)` }}>
        {visible.map((item) => (
          <li
            key={item.seq}
            className={`message message--${item.kind.toLowerCase()}${
              highlightNoteIds.size > 0 && item.matchedNoteIds.some((id) => highlightNoteIds.has(id))
                ? ' message--noted'
                : ''
            }`}
            style={{ minHeight: ITEM_HEIGHT }}
            data-seq={item.seq}
          >
            <div className="message__meta">
              <span className="message__kind">{translate(KIND_LABEL[item.kind] ?? 'feed.kind.SYSTEM')}</span>
              <span className="message__author">
                {item.kind === 'SYSTEM'
                  ? translate('feed.system.author')
                  : item.hiddenAuthor
                    ? translate('feed.hiddenAuthor')
                    : (item.characterId ? names.get(item.characterId) : null) ??
                      translate('feed.masterAuthor')}
              </span>
              <time className="message__time" dateTime={item.createdAt}>
                {new Date(item.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              </time>
            </div>
            <p className="message__body">{bodyOf(item)}</p>
            {item.matchedNoteIds.length > 0 ? (
              <p className="message__notes">
                {item.matchedNoteIds
                  .map((id) => noteTitles.get(id) ?? translate('feed.note'))
                  .join(', ')}
              </p>
            ) : null}
          </li>
        ))}
      </ol>
      {loadingOlder ? (
        <p className="feed__loading" role="status">
          {translate('feed.loadingOlder')}
        </p>
      ) : null}
      {canLoadMore ? (
        <div className="feed__more">
          <button type="button" className="button button--ghost" onClick={onLoadMore}>
            {translate('feed.loadMore')}
          </button>
        </div>
      ) : null}
    </div>
  );
}
