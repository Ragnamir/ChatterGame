import { useEffect, useMemo, useState } from 'react';
import { gamesApi } from '../api/endpoints';
import type { Game } from '../api/types';
import { GameCard } from '../components/GameCard';
import { Notice, describeError } from '../components/Notice';
import { useSession } from '../session/SessionProvider';
import { translate } from '../i18n/ru';
import {
  EMPTY_FILTERS,
  filterGames,
  hasActiveFilters,
  type GameFilters,
} from '../lib/gameFilters';
import type { Phase, Readiness, Visibility } from '../api/types';

const READINESS: readonly (Readiness | 'ALL')[] = ['ALL', 'DRAFT', 'READY'];
const VISIBILITY: readonly (Visibility | 'ALL')[] = ['ALL', 'PRIVATE', 'PUBLIC'];
const PHASE: readonly (Phase | 'ALL')[] = ['ALL', 'NOT_STARTED', 'RUNNING', 'FINISHED'];

const LABELS = {
  readiness: {
    ALL: 'games.filters.all',
    DRAFT: 'games.readiness.DRAFT',
    READY: 'games.readiness.READY',
  },
  visibility: {
    ALL: 'games.filters.all',
    PRIVATE: 'games.visibility.PRIVATE',
    PUBLIC: 'games.visibility.PUBLIC',
  },
  phase: {
    ALL: 'games.filters.all',
    NOT_STARTED: 'games.phase.NOT_STARTED',
    RUNNING: 'games.phase.RUNNING',
    FINISHED: 'games.phase.FINISHED',
  },
} as const;

export function GamesPage() {
  const { user } = useSession();
  const [games, setGames] = useState<Game[] | null>(null);
  const [filters, setFilters] = useState<GameFilters>(EMPTY_FILTERS);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    gamesApi
      .listMine()
      .then((loaded) => {
        if (alive) {
          setGames(loaded);
        }
      })
      .catch((error: unknown) => {
        if (alive) {
          setFailure(describeError(error));
          setGames([]);
        }
      });
    return () => {
      alive = false;
    };
  }, []);

  const visible = useMemo(() => (games ? filterGames(games, filters) : []), [games, filters]);
  const filtered = hasActiveFilters(filters);

  if (!user) {
    return null;
  }

  return (
    <>
      <div className="page__header">
        <h1 className="page__title">{translate('games.title')}</h1>
      </div>
      <p className="page__subtitle">{translate('games.subtitle')}</p>

      {failure ? <Notice code={failure} /> : null}

      {games === null ? (
        <p className="loading" role="status">
          {translate('app.loading')}
        </p>
      ) : games.length === 0 && !failure ? (
        <div className="empty">
          <h2>{translate('games.emptyTitle')}</h2>
          <p>{translate('games.emptyBody')}</p>
        </div>
      ) : (
        <>
          <form className="filters" aria-label={translate('aria.filters')} onSubmit={(event) => event.preventDefault()}>
            <fieldset className="filters__grid" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="filters__legend">{translate('games.filters.legend')}</legend>
              <div className="field">
                <label htmlFor="filter-readiness">{translate('games.filters.readiness')}</label>
                <select
                  id="filter-readiness"
                  value={filters.readiness}
                  onChange={(event) =>
                    setFilters({ ...filters, readiness: event.target.value as GameFilters['readiness'] })
                  }
                >
                  {READINESS.map((value) => (
                    <option key={value} value={value}>
                      {translate(LABELS.readiness[value])}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="filter-visibility">{translate('games.filters.visibility')}</label>
                <select
                  id="filter-visibility"
                  value={filters.visibility}
                  onChange={(event) =>
                    setFilters({ ...filters, visibility: event.target.value as GameFilters['visibility'] })
                  }
                >
                  {VISIBILITY.map((value) => (
                    <option key={value} value={value}>
                      {translate(LABELS.visibility[value])}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="filter-phase">{translate('games.filters.phase')}</label>
                <select
                  id="filter-phase"
                  value={filters.phase}
                  onChange={(event) =>
                    setFilters({ ...filters, phase: event.target.value as GameFilters['phase'] })
                  }
                >
                  {PHASE.map((value) => (
                    <option key={value} value={value}>
                      {translate(LABELS.phase[value])}
                    </option>
                  ))}
                </select>
              </div>
            </fieldset>
            <div className="filters__footer">
              <p aria-live="polite">{translate('games.resultCount', { count: visible.length })}</p>
              {filtered ? (
                <button
                  type="button"
                  className="button button--ghost"
                  onClick={() => setFilters(EMPTY_FILTERS)}
                >
                  {translate('games.filters.reset')}
                </button>
              ) : null}
            </div>
          </form>

          {visible.length === 0 ? (
            <div className="empty">
              <h2>{translate('games.emptyTitle')}</h2>
              <p>{translate('games.filteredEmpty')}</p>
            </div>
          ) : (
            <ul className="game-list">
              {visible.map((game) => (
                <GameCard key={game.id} game={game} viewerId={user.id} />
              ))}
            </ul>
          )}
        </>
      )}
    </>
  );
}
