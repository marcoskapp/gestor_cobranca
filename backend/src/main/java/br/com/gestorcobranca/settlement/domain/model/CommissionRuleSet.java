package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Versão das regras de comissionamento da assessoria num contrato de serviço
 * ({@code organization.ServiceContract}). Rascunho editável → publicada com início de vigência (imutável;
 * a versão anterior recebe o fim da vigência). Quem publica não pode ser quem editou as regras por
 * último (dupla checagem da transcrição do contrato). A apuração usa a versão vigente na data do
 * pagamento; publicação não pode alcançar recebimento já apurado (reprodutibilidade, RB08).
 */
@Entity
@Table(name = "commission_rule_set", schema = "settlement")
public class CommissionRuleSet extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(name = "rule_version", nullable = false, updatable = false)
    private int ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RuleSetStatus status;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", nullable = false)
    private String rules;

    @Column(name = "rules_schema_version", nullable = false)
    private int rulesSchemaVersion;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "rules_edited_by", nullable = false)
    private UUID rulesEditedBy;

    @Column(name = "rules_edited_at", nullable = false)
    private Instant rulesEditedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected CommissionRuleSet() {
    }

    public CommissionRuleSet(UUID creditorId, UUID contractId, int ruleVersion, String rulesJson, int schemaVersion,
                             String notes, UUID editor, Instant now) {
        Guard.require(creditorId != null && contractId != null, "commission_rule_set.contract_required",
                "Credor e contrato obrigatórios");
        Guard.require(ruleVersion > 0, "commission_rule_set.version_invalid", "Versão inválida");
        this.creditorId = creditorId;
        this.contractId = contractId;
        this.ruleVersion = ruleVersion;
        this.status = RuleSetStatus.DRAFT;
        edit(rulesJson, schemaVersion, notes, editor, now);
    }

    public void updateDraft(String rulesJson, int schemaVersion, String notes, UUID editor, Instant now) {
        Guard.requireState(status == RuleSetStatus.DRAFT, "commission_rule_set.not_draft",
                "Somente rascunho pode ser alterado; crie uma nova versão");
        edit(rulesJson, schemaVersion, notes, editor, now);
    }

    private void edit(String rulesJson, int schemaVersion, String notes, UUID editor, Instant now) {
        Guard.require(rulesJson != null && !rulesJson.isBlank(), "commission_rule_set.rules_required",
                "Regras obrigatórias");
        Guard.require(notes == null || notes.length() <= 2000, "commission_rule_set.notes_too_long",
                "Observações até 2000 caracteres");
        this.rules = rulesJson;
        this.rulesSchemaVersion = schemaVersion;
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
        this.rulesEditedBy = editor;
        this.rulesEditedAt = now;
    }

    public void publish(LocalDate effectiveFrom, UUID publisher, Instant now) {
        Guard.requireState(status == RuleSetStatus.DRAFT, "commission_rule_set.not_draft", "Versão já publicada");
        Guard.require(effectiveFrom != null, "commission_rule_set.effective_from_required",
                "Informe o início da vigência");
        Guard.require(!publisher.equals(rulesEditedBy), "commission_rule_set.self_publish",
                "Quem editou as regras não pode publicá-las: peça a conferência de outra pessoa");
        this.status = RuleSetStatus.PUBLISHED;
        this.effectiveFrom = effectiveFrom;
        this.publishedBy = publisher;
        this.publishedAt = now;
    }

    /** Fim da vigência quando a versão seguinte é publicada (ou o contrato encerra). */
    public void endOn(LocalDate lastDay) {
        Guard.requireState(status == RuleSetStatus.PUBLISHED && effectiveTo == null,
                "commission_rule_set.already_ended", "Vigência já encerrada");
        Guard.require(lastDay != null && !lastDay.isBefore(effectiveFrom), "commission_rule_set.end_invalid",
                "A nova vigência deve começar depois do início da versão anterior");
        this.effectiveTo = lastDay;
    }

    public void discard() {
        Guard.requireState(status == RuleSetStatus.DRAFT, "commission_rule_set.not_draft",
                "Somente rascunho pode ser descartado");
        this.status = RuleSetStatus.DISCARDED;
    }

    public boolean covers(LocalDate date) {
        return status == RuleSetStatus.PUBLISHED && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    public UUID getCreditorId() { return creditorId; }
    public UUID getContractId() { return contractId; }
    public int getRuleVersion() { return ruleVersion; }
    public RuleSetStatus getStatus() { return status; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public String getRules() { return rules; }
    public int getRulesSchemaVersion() { return rulesSchemaVersion; }
    public String getNotes() { return notes; }
    public UUID getRulesEditedBy() { return rulesEditedBy; }
    public Instant getRulesEditedAt() { return rulesEditedAt; }
    public UUID getPublishedBy() { return publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
}
