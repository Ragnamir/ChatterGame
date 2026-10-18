import type { GraphNode } from '../api/types';
import { translate } from '../i18n/ru';
import type { VisibleTransition } from '../lib/room';

export interface TransitionMapProps {
  nodes: readonly GraphNode[];
  transitions: readonly VisibleTransition[];
  currentChatId: string | null;
  onSelect: (chatId: string) => void;
}

/**
 * Карта переходов (`PLAN F02`): узел — локация, ребро — переход. Закрытый переход
 * показывается с причиной, неизвестный не показывается вовсе — это свойство уже
 * обеспечено сервером, здесь оно только отображается.
 */
export function TransitionMap({ nodes, transitions, currentChatId, onSelect }: TransitionMapProps) {
  return (
    <section className="panel map" aria-labelledby="map-title">
      <h2 id="map-title">{translate('map.title')}</h2>
      <p className="panel__hint">{translate('map.hint')}</p>
      <ul className="map__nodes">
        {nodes.map((node) => {
          const outgoing = transitions.filter((edge) => edge.fromChatId === node.id);
          const incoming = transitions.filter((edge) => edge.toChatId === node.id);
          return (
            <li
              key={node.id}
              className={`map__node${currentChatId === node.id ? ' map__node--current' : ''}${
                node.archived ? ' map__node--archived' : ''
              }`}
            >
              <button
                type="button"
                className="map__button"
                aria-current={currentChatId === node.id ? 'true' : undefined}
                onClick={() => onSelect(node.id)}
              >
                {node.title}
              </button>
              {node.archived ? (
                <p className="map__reason">
                  {translate('map.archived', { reason: node.archivedReason ?? translate('map.noReason') })}
                </p>
              ) : null}
              {outgoing.length > 0 ? (
                <ul className="map__edges">
                  {outgoing.map((edge) => {
                    const target = nodes.find((entry) => entry.id === edge.toChatId);
                    return (
                      <li key={edge.id} className={`map__edge map__edge--${edge.state.toLowerCase()}`}>
                        <span aria-hidden="true">→</span>{' '}
                        <span>{edge.title ?? target?.title ?? translate('map.unknownTarget')}</span>
                        {edge.state === 'CLOSED' ? (
                          <span className="map__closed">
                            {translate('map.closed', {
                              reason: edge.reason ?? translate('map.noReason'),
                            })}
                          </span>
                        ) : null}
                      </li>
                    );
                  })}
                </ul>
              ) : null}
              {incoming.length > 0 ? (
                <p className="map__incoming">
                  {translate('map.incoming', { count: incoming.length })}
                </p>
              ) : null}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
