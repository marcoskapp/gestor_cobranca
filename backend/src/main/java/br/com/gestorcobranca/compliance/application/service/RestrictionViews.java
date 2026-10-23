package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionView;
import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;

import java.time.Instant;

/** Conversão de restrições em views (contato sempre mascarado). */
final class RestrictionViews {

    private RestrictionViews() {
    }

    static RestrictionView view(ContactRestriction r, Instant now) {
        return new RestrictionView(r.getId(), r.getDebtorId(), r.getCreditorId(), ComplianceEnums.name(r.getChannel()),
                r.getType().name(), ComplianceEnums.name(r.getContactValueType()), r.getContactValueMasked(), r.getReason(),
                r.getSource().name(), r.getStartsAt(), r.getEndsAt(), r.getStatus().name(), r.isEffectiveAt(now),
                r.getRevokedAt(), r.getRevokedBy(), r.getRevokedReason(), r.getOriginRequestId(),
                r.getEvidenceFileKey() != null, r.getEvidenceFileName(), r.getEvidenceSha256(), r.getCreatedAt(),
                r.getCreatedBy());
    }
}
