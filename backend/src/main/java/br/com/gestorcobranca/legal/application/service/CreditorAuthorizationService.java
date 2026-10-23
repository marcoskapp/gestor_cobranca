package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases;
import br.com.gestorcobranca.legal.domain.model.AuthorizationChannel;
import br.com.gestorcobranca.legal.domain.model.AuthorizationStatus;
import br.com.gestorcobranca.legal.domain.model.CreditorAuthorization;
import br.com.gestorcobranca.legal.domain.model.DocumentKind;
import br.com.gestorcobranca.legal.domain.model.LegalDocument;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.repository.CreditorAuthorizationRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDocumentRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class CreditorAuthorizationService implements CreditorAuthorizationUseCases {

    private final CreditorAuthorizationRepository authorizations;
    private final LegalMatterRepository matters;
    private final LegalDocumentRepository documents;
    private final AuthorizationRecorder recorder;
    private final MatterRecorder matterRecorder;
    private final LegalAccess access;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final Clock clock;

    CreditorAuthorizationService(CreditorAuthorizationRepository authorizations, LegalMatterRepository matters,
                                 LegalDocumentRepository documents, AuthorizationRecorder recorder,
                                 MatterRecorder matterRecorder, LegalAccess access, AccessScope accessScope,
                                 AuditTrail auditTrail, BusinessEventLogger businessEvents, LegalMetrics metrics,
                                 Clock clock) {
        this.authorizations = authorizations;
        this.matters = matters;
        this.documents = documents;
        this.recorder = recorder;
        this.matterRecorder = matterRecorder;
        this.access = access;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public AuthorizationView request(UUID matterId, RequestCommand command) {
        LegalMatter m = access.forCommand(matterId);
        Guard.requireState(m.getStage().acceptsActions(), "legal_authorization.stage_invalid",
                "Solicitação de autorização só após a triagem (aguardando autorização ou em atuação)");
        Set<LegalRoute> routes = LegalViews.parseSet(LegalRoute.class, command.routes(), "legal_authorization.route_invalid",
                "Via");
        Guard.require(command.budget() != null, "legal_authorization.budget_invalid", "Orçamento de custas obrigatório");
        return LegalViews.authorization(recorder.open(m, routes, Money.of(command.budget()), command.note(), "request"));
    }

    @Override
    public AuthorizationView decide(UUID authorizationId, DecisionCommand command) {
        accessScope.requirePermission(Permission.LEGAL_AUTHORIZE);
        CreditorAuthorization authorization = authorizations.findById(authorizationId)
                .orElseThrow(() -> ResourceNotFoundException.of(AuthorizationRecorder.ENTITY, authorizationId));
        LegalMatter m = access.forCommand(authorization.getMatterId());
        m.requireOpen();
        AuthorizationStatus decision = LegalViews.parse(AuthorizationStatus.class, command.decision(),
                "legal_authorization.decision_invalid", "Decisão");
        AuthorizationChannel channel = LegalViews.parse(AuthorizationChannel.class, command.channel(),
                "legal_authorization.channel_invalid", "Meio da decisão");
        if (command.evidenceDocumentId() != null) {
            LegalDocument evidence = documents.findById(command.evidenceDocumentId())
                    .filter(d -> d.getMatterId().equals(m.getId()))
                    .orElseThrow(() -> new BusinessRuleException("legal_authorization.evidence_invalid",
                            "Evidência deve ser documento do próprio caso jurídico"));
            Guard.require(evidence.getKind() == DocumentKind.AUTHORIZATION_EVIDENCE,
                    "legal_authorization.evidence_kind", "A evidência deve ser do tipo AUTHORIZATION_EVIDENCE");
        }
        UUID actorId = access.actor().id();
        Instant now = Instant.now(clock);
        CreditorAuthorization previousGrant = null;
        switch (decision) {
            case GRANTED -> {
                Set<LegalRoute> routes = LegalViews.parseSet(LegalRoute.class, command.routes(),
                        "legal_authorization.route_invalid", "Via");
                Guard.require(command.approvedBudget() != null, "legal_authorization.budget_invalid",
                        "Orçamento aprovado obrigatório");
                previousGrant = authorizations.findFirstByMatterIdAndStatus(m.getId(), AuthorizationStatus.GRANTED)
                        .orElse(null);
                if (previousGrant != null) {
                    previousGrant.supersede();
                    authorizations.save(previousGrant);
                    authorizations.flush();
                }
                authorization.grant(routes, Money.of(command.approvedBudget()), command.decisionDate(), channel,
                        command.representative(), command.evidenceDocumentId(), command.note(), LegalViews.today(clock),
                        actorId, now);
                m.applyAuthorization(authorization.getId(), authorization.getApprovedBudget());
                matters.save(m);
            }
            case DENIED -> authorization.deny(command.decisionDate(), channel, command.representative(),
                    command.evidenceDocumentId(), command.note(), LegalViews.today(clock), actorId, now);
            default -> throw new BusinessRuleException("legal_authorization.decision_invalid", "Decisão deve ser GRANTED ou DENIED");
        }
        authorizations.save(authorization);
        auditTrail.record(AuditEntry.of("creditor_authorization.decided", AuthorizationRecorder.ENTITY, authorization.getId())
                .creditor(m.getCreditorId()).reason(authorization.getDecisionNote())
                .change("status", AuthorizationStatus.REQUESTED, authorization.getStatus())
                .created("authorizedRoutes", LegalViews.names(authorization.getAuthorizedRoutes()).toString())
                .created("approvedBudget", authorization.getApprovedBudget())
                .created("decisionDate", authorization.getDecisionDate())
                .created("channel", authorization.getChannel())
                .created("creditorRepresentative", authorization.getCreditorRepresentative())
                .created("evidenceDocumentId", authorization.getEvidenceDocumentId())
                .meta("matterId", m.getId())
                .meta("supersededAuthorizationId", previousGrant == null ? null : previousGrant.getId()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_AUTHORIZATION_DECIDED, m)
                .amount(authorization.getApprovedBudget())
                .attr("decision", authorization.getStatus()).attr("channel", authorization.getChannel())
                .attr("routes", String.join(",", LegalViews.names(authorization.getAuthorizedRoutes())))
                .attr("requestedBudget", authorization.getRequestedBudget().amount())
                .attr("hasEvidence", authorization.getEvidenceDocumentId() != null)
                .attr("supplementary", previousGrant != null)
                .attr("daysToDecision", java.time.temporal.ChronoUnit.DAYS.between(
                        authorization.getCreatedAt().atZone(br.com.gestorcobranca.shared.config.TimeConfig.BUSINESS_ZONE)
                                .toLocalDate(), authorization.getDecisionDate()))
                .attr("stage", m.getStage()));
        metrics.authorization(authorization.getStatus());
        return LegalViews.authorization(authorization);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuthorizationView> list(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return authorizations.findByMatterIdOrderByCreatedAtDesc(m.getId()).stream().map(LegalViews::authorization)
                .toList();
    }
}
