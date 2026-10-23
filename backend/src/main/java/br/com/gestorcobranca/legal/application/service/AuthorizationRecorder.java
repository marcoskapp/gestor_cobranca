package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.model.AuthorizationStatus;
import br.com.gestorcobranca.legal.domain.model.CreditorAuthorization;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.repository.CreditorAuthorizationRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Abertura de solicitação de autorização ao credor, comum à transição de fase e à solicitação suplementar. */
@Component
class AuthorizationRecorder {

    static final String ENTITY = "creditor_authorization";

    private final CreditorAuthorizationRepository authorizations;
    private final MatterRecorder matterRecorder;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;

    AuthorizationRecorder(CreditorAuthorizationRepository authorizations, MatterRecorder matterRecorder,
                          AuditTrail auditTrail, BusinessEventLogger businessEvents) {
        this.authorizations = authorizations;
        this.matterRecorder = matterRecorder;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
    }

    CreditorAuthorization open(LegalMatter matter, Set<LegalRoute> routes, Money budget, String note, String cause) {
        matter.requireOpen();
        if (authorizations.findFirstByMatterIdAndStatus(matter.getId(), AuthorizationStatus.REQUESTED).isPresent()) {
            throw new ConflictException("legal_authorization.pending_exists",
                    "Já existe solicitação de autorização pendente para o caso jurídico");
        }
        CreditorAuthorization authorization = authorizations.save(new CreditorAuthorization(matter.getId(),
                matter.getCreditorId(), routes, budget, note));
        auditTrail.record(AuditEntry.of("creditor_authorization.requested", ENTITY, authorization.getId())
                .creditor(matter.getCreditorId())
                .created("matterId", matter.getId())
                .created("requestedRoutes", LegalViews.names(routes).toString())
                .created("requestedBudget", budget)
                .meta("cause", cause));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_AUTHORIZATION_REQUESTED, matter).amount(budget)
                .attr("routes", String.join(",", LegalViews.names(routes))).attr("stage", matter.getStage())
                .attr("cause", cause));
        return authorization;
    }
}
