import { Link } from 'react-router-dom';
import type { Game } from '../api/types';
import { translate } from '../i18n/ru';

const READINESS = { DRAFT: 'games.readiness.DRAFT', READY: 'games.readiness.READY' } as const;
const VISIBILITY = {
  PRIVATE: 'games.visibility.PRIVATE',
  PUBLIC: 'games.visibility.PUBLIC',
} as const;
const PHASE = {
  NOT_STARTED: 'games.phase.NOT_STARTED',
  RUNNING: 'games.phase.RUNNING',
  FINISHED: 'games.phase.FINISHED',
} as const;

export function GameCard({ game, viewerId }: { game: Game; viewerId: string }) {
  const isMaster = game.masterUserId === viewerId;
  return (
    <li className="game-card">
      <div className="game-card__head">
        <h2 className="game-card__title">{game.title}</h2>
        <ul className="badges">
          <li className="badge">{translate(READINESS[game.readiness])}</li>
          <li className="badge">{translate(VISIBILITY[game.visibility])}</li>
          <li className="badge">{translate(PHASE[game.phase])}</li>
          {isMaster ? <li className="badge badge--accent">{translate('games.badge.master')}</li> : null}
          {game.paused ? <li className="badge">{translate('games.badge.paused')}</li> : null}
          {game.archived ? <li className="badge">{translate('games.badge.archived')}</li> : null}
          {game.readOnly ? <li className="badge">{translate('games.badge.readOnly')}</li> : null}
        </ul>
      </div>
      {game.publicDescription ? <p className="game-card__desc">{game.publicDescription}</p> : null}
      {game.paused && game.pauseReason ? (
        <p className="game-card__note">{translate('games.pausedReason', { reason: game.pauseReason })}</p>
      ) : null}
      {game.archived && game.archivedReason ? (
        <p className="game-card__note">
          {translate('games.archivedReason', { reason: game.archivedReason })}
        </p>
      ) : null}
      <div className="game-card__actions">
        <Link className="button button--primary" to={`/games/${game.id}`}>
          {translate('games.open')}
        </Link>
      </div>
    </li>
  );
}
