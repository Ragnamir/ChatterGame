import type { Game, Membership, Turn } from '../api/types';
import { translate } from '../i18n/ru';

export interface TurnPanelProps {
  turn: Turn | null;
  characterNames: ReadonlyMap<string, string>;
  canControl: boolean;
  onOpen: () => Promise<void>;
  onClose: () => Promise<void>;
}

/**
 * Состояние хода (`REQUIREMENTS §14`). `open === false` — это «ход не открыт мастером»,
 * без ссылки на время: в пошаговом режиме по времени ничего не вычисляется (`D-060`).
 */
export function TurnPanel({ turn, characterNames, canControl, onOpen, onClose }: TurnPanelProps) {
  if (!turn || !turn.turnBased) {
    return null;
  }
  return (
    <section className="panel turn" aria-labelledby="turn-title">
      <h2 id="turn-title">{translate('turn.title')}</h2>
      <p className="turn__state" role="status">
        {turn.open
          ? translate('turn.open', { n: turn.n })
          : translate('turn.closed')}
      </p>
      <p className="panel__hint">{translate('turn.hint')}</p>
      <ul className="turn__budgets">
        {turn.budgets.map((budget) => (
          <li key={budget.characterId} className="turn__budget">
            <span>{characterNames.get(budget.characterId) ?? budget.characterId.slice(0, 8)}</span>
            <span className={`badge${budget.speechUsed ? ' badge--spent' : ''}`}>
              {translate(budget.speechUsed ? 'turn.speechSpent' : 'turn.speechFree')}
            </span>
            <span className={`badge${budget.actionUsed ? ' badge--spent' : ''}`}>
              {translate(budget.actionUsed ? 'turn.actionSpent' : 'turn.actionFree')}
            </span>
          </li>
        ))}
      </ul>
      {canControl ? (
        <div className="form__actions">
          <button
            type="button"
            className="button button--primary"
            disabled={turn.open}
            onClick={() => void onOpen()}
          >
            {translate('turn.openAction')}
          </button>
          <button
            type="button"
            className="button button--ghost"
            disabled={!turn.open}
            onClick={() => void onClose()}
          >
            {translate('turn.closeAction')}
          </button>
        </div>
      ) : null}
    </section>
  );
}

export interface GameHeaderProps {
  game: Game;
  membership: Membership | null;
}

export function GameHeader({ game, membership }: GameHeaderProps) {
  return (
    <div className="page__header">
      <div>
        <p className="eyebrow">{translate('room.eyebrow')}</p>
        <h1 className="page__title">{game.title}</h1>
        {game.publicDescription ? <p className="page__subtitle">{game.publicDescription}</p> : null}
      </div>
      <ul className="badges">
        {membership ? (
          <li className="badge badge--accent">{translate(`membership.role.${membership.role}`)}</li>
        ) : null}
        {game.paused ? (
          <li className="badge">
            {translate('room.paused', { reason: game.pauseReason ?? translate('map.noReason') })}
          </li>
        ) : null}
        {game.readOnly ? <li className="badge">{translate('games.badge.readOnly')}</li> : null}
        <li className="badge">{translate(`games.phase.${game.phase}`)}</li>
      </ul>
    </div>
  );
}
