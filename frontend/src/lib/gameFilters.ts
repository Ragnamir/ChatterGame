import type { Phase, Readiness, Visibility } from '../api/types';
import type { Game } from '../api/types';

export type ReadinessFilter = Readiness | 'ALL';
export type VisibilityFilter = Visibility | 'ALL';
export type PhaseFilter = Phase | 'ALL';

export interface GameFilters {
  readiness: ReadinessFilter;
  visibility: VisibilityFilter;
  phase: PhaseFilter;
}

export const EMPTY_FILTERS: GameFilters = {
  readiness: 'ALL',
  visibility: 'ALL',
  phase: 'ALL',
};

/**
 * Фильтр списка на клиенте: `GET /games` отдаёт все игры пользователя без параметров,
 * а три оси независимы (`D-003`), поэтому «все» — отдельное значение, а не пустая строка.
 */
export function filterGames(games: readonly Game[], filters: GameFilters): Game[] {
  return games.filter((game) => {
    const readiness = filters.readiness === 'ALL' || game.readiness === filters.readiness;
    const visibility = filters.visibility === 'ALL' || game.visibility === filters.visibility;
    const phase = filters.phase === 'ALL' || game.phase === filters.phase;
    return readiness && visibility && phase;
  });
}

export function hasActiveFilters(filters: GameFilters): boolean {
  return (
    filters.readiness !== 'ALL' || filters.visibility !== 'ALL' || filters.phase !== 'ALL'
  );
}
