import type { GameRequest, RequestOperation } from '../api/types';
import type { MessageKey } from '../i18n/ru';

/**
 * Подписи диффа заявки (`D-041`). Собираются здесь, а не в компоненте: панель мастера
 * показывает те же строки в очереди и в карточке, и две сборки разошлись бы на первом же
 * новом виде операции.
 */
export interface DiffLine {
  labelKey: MessageKey;
  before: string | null;
  after: string | null;
}

const FIELD_KEY: Record<string, MessageKey> = {
  name: 'request.field.name',
  appearance: 'request.field.appearance',
  publicHistory: 'request.field.publicHistory',
  privateHistory: 'request.field.privateHistory',
};

/** Операции, которые ждут решения: применённые и отклонённые мастер уже видел. */
export function pendingOrdinals(request: GameRequest): number[] {
  return request.operations
    .filter((operation) => operation.status === 'PENDING')
    .map((operation) => operation.ordinal);
}

/** Операции, которые можно откатить: применённые и обратимые (`D-010`, `D-041`). */
export function rollbackOrdinals(request: GameRequest): number[] {
  return request.operations
    .filter((operation) => operation.status === 'APPLIED' && operation.reversible)
    .map((operation) => operation.ordinal);
}

export function awaitingDecision(request: GameRequest): boolean {
  return request.status === 'PENDING' || request.status === 'SUCCEEDED';
}

/**
 * Ответ модели приходит строкой `код: подробность` (`D-043`). Код переводится словарём,
 * подробность показывается как есть: это диагностика провайдера, а не текст интерфейса.
 */
export function llmError(request: GameRequest): { key: MessageKey; detail: string | null } | null {
  if (request.status !== 'FAILED' || !request.error) {
    return null;
  }
  const separator = request.error.indexOf(':');
  const code = separator < 0 ? request.error : request.error.slice(0, separator);
  const detail = separator < 0 ? null : request.error.slice(separator + 1).trim();
  const key = `request.error.${code}`;
  return { key: key as MessageKey, detail: detail || null };
}

function text(value: unknown): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  return typeof value === 'string' ? value : JSON.stringify(value);
}

function field(payload: Record<string, unknown>, name: string): string | null {
  return text(payload[name]);
}

/**
 * Дифф операции: что было и что станет. Прежнее значение берётся из снимка `oldValue`,
 * снятого при применении, а у неприменённой операции его нет — тогда показывается только
 * новое значение, и это честнее, чем читать журнал вторым запросом ради подсказки.
 */
export function diffOf(operation: RequestOperation): DiffLine[] {
  const payload = operation.payload ?? {};
  const before = operation.oldValue ?? {};
  switch (operation.op) {
    case 'update_character_field': {
      const name = field(payload, 'field') ?? '';
      return [
        {
          labelKey: FIELD_KEY[name] ?? 'request.field.other',
          before: field(before, 'value'),
          after: field(payload, 'value'),
        },
      ];
    }
    case 'create_item':
      return [
        {
          labelKey: 'request.diff.item',
          before: null,
          after: [field(payload, 'name'), field(payload, 'size')]
            .filter((part) => part !== null)
            .join(', '),
        },
      ];
    case 'move_character':
      return [
        {
          labelKey: 'request.diff.location',
          before: field(before, 'chatId'),
          after: field(payload, 'transitionId') ?? field(payload, 'chatId'),
        },
      ];
    case 'post_master_message':
    case 'post_character_message':
      return [{ labelKey: 'request.diff.message', before: null, after: field(payload, 'body') }];
    case 'update_storage_contents': {
      const lines: DiffLine[] = [
        {
          labelKey: 'request.diff.storage',
          before: field(before, 'title'),
          after: field(payload, 'title'),
        },
        {
          labelKey: 'request.diff.description',
          before: field(before, 'description'),
          after: field(payload, 'description'),
        },
      ];
      return lines.filter((line) => line.before !== null || line.after !== null);
    }
    case 'create_note':
      return [{ labelKey: 'request.diff.note', before: null, after: field(payload, 'body') }];
    default:
      return [{ labelKey: 'request.diff.other', before: null, after: text(payload) }];
  }
}
