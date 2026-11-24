package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Recusas do convite auditadas em transação própria: sobrevivem ao rollback do caso de uso que as lança (decisão 1395). */
@Component
class CreditorInviteRefusals {

    private final AuditTrail auditTrail;

    CreditorInviteRefusals(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void internalUserEmail(UUID creditorId) {
        auditTrail.record(AuditEntry.of("creditor_user.invite_refused", "creditor", creditorId)
                .meta("reason", "internal_user_email"));
    }
}
