package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Autorização formal do credor (etapa 3): vias e orçamento de custas solicitados, decisão do
 * credor (concedida/negada) com a data, o meio, o representante que decidiu e a evidência
 * (documento do caso jurídico) — registrada por quem tem {@code LEGAL_AUTHORIZE}. A concessão
 * pode aprovar parte das vias e um orçamento diferente do solicitado. Uma nova concessão
 * substitui a anterior (SUPERSEDED); só há uma solicitação pendente por vez.
 */
@Entity
@Table(name = "creditor_authorization", schema = "legal")
public class CreditorAuthorization extends TenantScopedEntity {

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuthorizationStatus status;

    @Column(name = "requested_routes", nullable = false, length = 300, updatable = false)
    private String requestedRoutes;

    @Column(name = "requested_budget", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money requestedBudget;

    @Column(name = "request_note", length = 500, updatable = false)
    private String requestNote;

    @Column(name = "authorized_routes", length = 300)
    private String authorizedRoutes;

    @Column(name = "approved_budget", precision = 19, scale = 2)
    private Money approvedBudget;

    @Column(name = "decision_date")
    private LocalDate decisionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20)
    private AuthorizationChannel channel;

    @Column(name = "creditor_representative", length = 150)
    private String creditorRepresentative;

    @Column(name = "evidence_document_id")
    private UUID evidenceDocumentId;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    protected CreditorAuthorization() {
    }

    public CreditorAuthorization(UUID matterId, UUID creditorId, Set<LegalRoute> routes, Money budget, String note) {
        Guard.require(routes != null && !routes.isEmpty(), "legal_authorization.routes_required",
                "Informe as vias a autorizar");
        Guard.require(budget != null && !budget.isNegative(), "legal_authorization.budget_invalid",
                "Orçamento de custas inválido");
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.status = AuthorizationStatus.REQUESTED;
        this.requestedRoutes = RouteSet.join(routes);
        this.requestedBudget = budget;
        this.requestNote = text(note, 500);
    }

    /**
     * @param representative nome de quem decidiu pelo credor (preposto do credor, nunca do devedor)
     * @param evidenceDocumentId documento do próprio caso jurídico (validado pelo caso de uso)
     */
    public void grant(Set<LegalRoute> routes, Money budget, LocalDate decisionDate, AuthorizationChannel channel,
                      String representative, UUID evidenceDocumentId, String note, LocalDate today, UUID actorId,
                      Instant now) {
        requirePending();
        Guard.require(routes != null && !routes.isEmpty(), "legal_authorization.routes_required",
                "Informe as vias autorizadas");
        Set<LegalRoute> requested = getRequestedRoutes();
        if (!requested.containsAll(routes)) {
            throw new BusinessRuleException("legal_authorization.route_not_requested",
                    "A autorização só pode conceder vias solicitadas");
        }
        Guard.require(budget != null && !budget.isNegative(), "legal_authorization.budget_invalid",
                "Orçamento aprovado inválido");
        Guard.require(evidenceDocumentId != null, "legal_authorization.evidence_required",
                "Anexe a evidência da autorização do credor");
        decide(AuthorizationStatus.GRANTED, decisionDate, channel, representative, note, today, actorId, now);
        this.authorizedRoutes = RouteSet.join(routes);
        this.approvedBudget = budget;
        this.evidenceDocumentId = evidenceDocumentId;
    }

    public void deny(LocalDate decisionDate, AuthorizationChannel channel, String representative, UUID evidenceDocumentId,
                     String note, LocalDate today, UUID actorId, Instant now) {
        requirePending();
        Guard.require(note != null && !note.isBlank(), "legal_authorization.note_required",
                "Informe o motivo da negativa");
        decide(AuthorizationStatus.DENIED, decisionDate, channel, representative, note, today, actorId, now);
        this.evidenceDocumentId = evidenceDocumentId;
    }

    public void supersede() {
        Guard.requireState(status == AuthorizationStatus.GRANTED, "legal_authorization.not_granted",
                "Somente autorização concedida é substituída");
        this.status = AuthorizationStatus.SUPERSEDED;
    }

    private void decide(AuthorizationStatus outcome, LocalDate date, AuthorizationChannel ch, String representative,
                        String note, LocalDate today, UUID actorId, Instant now) {
        Guard.require(date != null && !date.isAfter(today), "legal_authorization.date_invalid",
                "Data da decisão obrigatória e não futura");
        Guard.require(ch != null, "legal_authorization.channel_required", "Meio da decisão obrigatório");
        String rep = text(representative, 150);
        Guard.require(rep != null, "legal_authorization.representative_required",
                "Informe quem decidiu pelo credor");
        this.status = outcome;
        this.decisionDate = date;
        this.channel = ch;
        this.creditorRepresentative = rep;
        this.decisionNote = text(note, 500);
        this.decidedAt = now;
        this.decidedBy = actorId;
    }

    private void requirePending() {
        Guard.requireState(status == AuthorizationStatus.REQUESTED, "legal_authorization.already_decided",
                "Autorização já decidida");
    }

    private static String text(String value, int max) {
        String v = value == null || value.isBlank() ? null : value.trim();
        Guard.require(v == null || v.length() <= max, "legal_authorization.text_too_long",
                "Texto deve ter até %d caracteres".formatted(max));
        return v;
    }

    public boolean covers(LegalRoute route) {
        return status == AuthorizationStatus.GRANTED && getAuthorizedRoutes().contains(route);
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public AuthorizationStatus getStatus() {
        return status;
    }

    public Set<LegalRoute> getRequestedRoutes() {
        return RouteSet.split(requestedRoutes);
    }

    public Money getRequestedBudget() {
        return requestedBudget;
    }

    public String getRequestNote() {
        return requestNote;
    }

    public Set<LegalRoute> getAuthorizedRoutes() {
        return RouteSet.split(authorizedRoutes);
    }

    public Money getApprovedBudget() {
        return approvedBudget;
    }

    public LocalDate getDecisionDate() {
        return decisionDate;
    }

    public AuthorizationChannel getChannel() {
        return channel;
    }

    public String getCreditorRepresentative() {
        return creditorRepresentative;
    }

    public UUID getEvidenceDocumentId() {
        return evidenceDocumentId;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }
}
