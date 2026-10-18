import type { Restriction } from '../api/types';
import { translate } from '../i18n/ru';
import { formatRestrictionExpiry, RESTRICTION_LABEL, restrictionsForViewer } from '../lib/turn';

export interface RestrictionNoticeProps {
  restrictions: readonly Restriction[];
  speakerId: string | null;
  membershipId: string | null;
}

/**
 * Действующие ограничения глазами игрока (`PLAN F03`): причина и срок видны до попытки
 * отправки, а не только после отказа сервера.
 */
export function RestrictionNotice({ restrictions, speakerId, membershipId }: RestrictionNoticeProps) {
  const active = restrictionsForViewer(restrictions, speakerId, membershipId);
  if (active.length === 0) {
    return null;
  }
  return (
    <section className="panel restrictions" aria-labelledby="restrictions-title">
      <h2 id="restrictions-title">{translate('room.restrictionsTitle')}</h2>
      <ul className="restrictions">
        {active.map((restriction) => (
          <li key={restriction.id} className="restrictions__row">
            <span>{translate(RESTRICTION_LABEL[restriction.kind])}</span>
            <span className="restrictions__reason">{restriction.reason}</span>
            <span className="badge">{formatRestrictionExpiry(restriction.expiresAt)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
