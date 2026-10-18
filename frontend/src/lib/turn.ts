import type {
  ChatKind,
  Game,
  MessageKind,
  Restriction,
  RestrictionKind,
  Turn,
} from '../api/types.ts';
import { translate, type MessageKey } from '../i18n/ru.ts';

export const RESTRICTION_LABEL: Record<RestrictionKind, MessageKey> = {
  NO_SPEECH: 'restriction.kind.NO_SPEECH',
  NO_ACTION: 'restriction.kind.NO_ACTION',
  NO_MOVE: 'restriction.kind.NO_MOVE',
  NO_JOKE: 'restriction.kind.NO_JOKE',
};

export interface TurnContext {
  phase: Game['phase'];
  turn: Turn;
  restrictions: readonly Restriction[];
}

/** Ограничение действует, пока не снято и не истекло: срок — факт, а не вычисление хода (`D-064`). */
export function isActiveRestriction(restriction: Restriction, now: number = Date.now()): boolean {
  if (restriction.revokedAt !== null) {
    return false;
  }
  return restriction.expiresAt === null || Date.parse(restriction.expiresAt) > now;
}

export function activeRestrictions(
  restrictions: readonly Restriction[],
  now: number = Date.now(),
): Restriction[] {
  return restrictions.filter((restriction) => isActiveRestriction(restriction, now));
}

/** Ограничения, адресованные смотрящему: персонаж — речи, действию и перемещению, участие — шутке (`D-099`). */
export function restrictionsForViewer(
  restrictions: readonly Restriction[],
  speakerId: string | null,
  membershipId: string | null,
  now: number = Date.now(),
): Restriction[] {
  return activeRestrictions(restrictions, now).filter((restriction) => {
    if (restriction.kind === 'NO_JOKE') {
      return restriction.subjectId === membershipId;
    }
    return restriction.subjectId === speakerId;
  });
}

/**
 * Почему тип сообщения закрыт (`PLAN F03`). Причина показывается до попытки отправки:
 * закрытый ход не мешает шутке, а исчерпанный бюджет — второму типу. Радио принимает
 * только речь (`D-039`), поэтому `ACTION` и `JOKE` там закрыты всегда.
 */
export function blockedKindReasons(
  context: TurnContext,
  chatKind: ChatKind,
  speakerId: string | null,
  membershipId: string | null,
  isMaster: boolean,
  now: number = Date.now(),
): Map<MessageKind, string> {
  const blocked = new Map<MessageKind, string>();
  // Личный чат с мастером не участвует в счёте хода (`D-013`): ни закрытый ход, ни
  // израсходованный в локации бюджет не должны его закрывать.
  const spendsTurn = chatKind !== 'MASTER_DM';
  if (context.phase === 'FINISHED') {
    blocked.set('SPEECH', translate('room.finalJokeOnly'));
    blocked.set('ACTION', translate('room.finalJokeOnly'));
  }
  if (spendsTurn && context.turn.turnBased && !context.turn.open) {
    blocked.set('SPEECH', translate('room.turnClosed'));
    blocked.set('ACTION', translate('room.turnClosed'));
  }
  if (chatKind === 'RADIO') {
    blocked.set('ACTION', translate('composer.radioSpeechOnly'));
    blocked.set('JOKE', translate('composer.radioSpeechOnly'));
  }
  if (chatKind === 'FINAL' && !isMaster) {
    blocked.set('MASTER', translate('composer.masterOnly'));
  }
  if (speakerId && spendsTurn) {
    const budget = context.turn.budgets.find((entry) => entry.characterId === speakerId);
    if (budget?.speechUsed) {
      blocked.set('SPEECH', translate('room.budgetSpeech'));
    }
    if (budget?.actionUsed) {
      blocked.set('ACTION', translate('room.budgetAction'));
    }
  }
  for (const restriction of activeRestrictions(context.restrictions, now)) {
    const reason = translate('room.restricted', { reason: restriction.reason });
    if (restriction.kind === 'NO_SPEECH' && restriction.subjectId === speakerId) {
      blocked.set('SPEECH', reason);
    }
    if (restriction.kind === 'NO_ACTION' && restriction.subjectId === speakerId) {
      blocked.set('ACTION', reason);
    }
    if (restriction.kind === 'NO_JOKE' && restriction.subjectId === membershipId) {
      blocked.set('JOKE', reason);
    }
  }
  return blocked;
}

/** Срок ограничения в поясе смотрящего; `timeZone` нужен тестам, чтобы формат не зависел от машины. */
export function formatRestrictionExpiry(expiresAt: string | null, timeZone?: string): string {
  if (expiresAt === null) {
    return translate('restriction.noDeadline');
  }
  const at = new Date(expiresAt).toLocaleString('ru-RU', {
    dateStyle: 'short',
    timeStyle: 'short',
    timeZone,
  });
  return translate('restriction.until', { at });
}
