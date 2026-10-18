export interface RenderWindow {
  start: number;
  end: number;
  offsetTop: number;
  totalHeight: number;
}

/**
 * Окно виртуализации (`PLAN F02`). Высота строки задаётся средней, а не измеряется:
 * лента однородна по типографике, и измерение каждой строки стоило бы дороже, чем
 * ошибка в несколько пикселей. `overscan` смягчает эту ошибку при быстром скролле.
 */
export function renderWindow(
  scrollTop: number,
  viewportHeight: number,
  itemHeight: number,
  total: number,
  overscan = 6,
): RenderWindow {
  const totalHeight = total * itemHeight;
  if (total === 0 || itemHeight <= 0) {
    return { start: 0, end: 0, offsetTop: 0, totalHeight: 0 };
  }
  const firstVisible = Math.floor(Math.max(0, scrollTop) / itemHeight);
  const visibleCount = Math.ceil(Math.max(0, viewportHeight) / itemHeight);
  const start = Math.max(0, firstVisible - overscan);
  const end = Math.min(total, firstVisible + visibleCount + overscan);
  return { start, end, offsetTop: start * itemHeight, totalHeight };
}

/** Автопрокрутка к низу — только если читатель уже внизу; иначе чтение истории ломается. */
export function isNearBottom(
  scrollTop: number,
  viewportHeight: number,
  totalHeight: number,
  threshold = 96,
): boolean {
  return totalHeight - (scrollTop + viewportHeight) <= threshold;
}
