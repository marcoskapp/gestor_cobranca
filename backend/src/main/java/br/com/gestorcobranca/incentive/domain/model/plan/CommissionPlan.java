package br.com.gestorcobranca.incentive.domain.model.plan;

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
 * Versão de um plano de comissão (§4). Um plano é identificado pelo {@code code}; cada versão é
 * uma linha com {@code planVersion} crescente. Rascunhos são editáveis; versões publicadas são
 * imutáveis (regras, código e início de vigência — reforçado por gatilho no banco) e só podem ter
 * a vigência encerrada (substituição por nova versão ou aposentadoria).
 * <p>
 * Vigência sempre em meses inteiros (início no dia 1, fim no último dia do mês), para que cada
 * período de apuração use exatamente uma versão — a vigente na data dos fatos.
 * <p>
 * As regras ficam serializadas ({@link PlanRules}, versão de esquema {@code rulesSchemaVersion})
 * e são validadas antes de toda gravação pela camada de aplicação.
 */
@Entity
@Table(name = "commission_plan", schema = "incentive")
public class CommissionPlan extends TenantScopedEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 40)
    private String code;

    @Column(name = "plan_version", nullable = false, updatable = false)
    private int planVersion;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PlanStatus status;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", nullable = false)
    private String rules;

    @Column(name = "rules_schema_version", nullable = false)
    private int rulesSchemaVersion;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "retired_by")
    private UUID retiredBy;

    protected CommissionPlan() {
    }

    /** Novo rascunho (primeira versão de um plano ou nova versão de um existente). */
    public CommissionPlan(String code, int planVersion, String name, String description, int priority, String rulesJson,
                          int rulesSchemaVersion) {
        Guard.require(code != null && code.matches("[A-Z0-9_]{2,40}"), "plan.code_invalid",
                "Código do plano: 2 a 40 caracteres em maiúsculas, dígitos ou _");
        Guard.require(planVersion > 0, "plan.version_invalid", "Versão inválida");
        this.code = code;
        this.planVersion = planVersion;
        this.status = PlanStatus.DRAFT;
        describe(name, description, priority);
        defineRules(rulesJson, rulesSchemaVersion);
    }

    public void describe(String name, String description, int priority) {
        requireDraft();
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 120, "plan.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(description == null || description.length() <= 1000, "plan.description_too_long",
                "Descrição até 1000 caracteres");
        Guard.require(priority >= 0 && priority <= 1000, "plan.priority_invalid", "Prioridade entre 0 e 1000");
        this.name = name.trim();
        this.description = description;
        this.priority = priority;
    }

    /** @param rulesJson regras já validadas e serializadas pela aplicação */
    public void defineRules(String rulesJson, int schemaVersion) {
        requireDraft();
        Guard.require(rulesJson != null && !rulesJson.isBlank(), "plan.rules_required", "Regras obrigatórias");
        this.rules = rulesJson;
        this.rulesSchemaVersion = schemaVersion;
    }

    /**
     * Publica a versão a partir de {@code effectiveFrom} (dia 1 de um mês).
     *
     * @param earliestAllowed primeiro dia aceito (mês corrente e não anterior a período já fechado)
     */
    public void publish(LocalDate effectiveFrom, LocalDate earliestAllowed, UUID actorId, Instant now) {
        requireDraft();
        Guard.require(effectiveFrom != null && effectiveFrom.getDayOfMonth() == 1, "plan.effective_from_invalid",
                "A vigência começa no primeiro dia de um mês");
        Guard.require(!effectiveFrom.isBefore(earliestAllowed), "plan.effective_from_retroactive",
                "Vigência não pode começar antes de " + earliestAllowed);
        this.status = PlanStatus.PUBLISHED;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = null;
        this.publishedAt = now;
        this.publishedBy = actorId;
    }

    /** Encerra a vigência por nova versão publicada a partir de {@code nextStart}. */
    public void supersede(LocalDate nextStart) {
        Guard.requireState(status == PlanStatus.PUBLISHED, "plan.not_published", "Versão não publicada");
        Guard.requireState(nextStart.isAfter(effectiveFrom), "plan.supersede_invalid",
                "A nova versão deve começar depois da versão vigente");
        LocalDate end = nextStart.minusDays(1);
        if (effectiveTo == null || effectiveTo.isAfter(end)) {
            this.effectiveTo = end;
        }
    }

    /** Aposenta a versão: vigência até {@code effectiveTo} (último dia de um mês). */
    public void retire(LocalDate effectiveTo, LocalDate earliestEnd, UUID actorId, Instant now) {
        Guard.requireState(status == PlanStatus.PUBLISHED, "plan.not_published", "Somente versão publicada pode ser aposentada");
        Guard.require(effectiveTo != null && effectiveTo.plusDays(1).getDayOfMonth() == 1, "plan.effective_to_invalid",
                "A vigência termina no último dia de um mês");
        Guard.require(!effectiveTo.isBefore(earliestEnd), "plan.effective_to_retroactive",
                "Vigência não pode terminar antes de " + earliestEnd);
        Guard.require(!effectiveTo.isBefore(effectiveFrom.minusDays(1)), "plan.effective_to_invalid",
                "Fim de vigência anterior ao início");
        if (this.effectiveTo == null || effectiveTo.isBefore(this.effectiveTo)) {
            this.effectiveTo = effectiveTo;
        }
        this.status = PlanStatus.RETIRED;
        this.retiredAt = now;
        this.retiredBy = actorId;
    }

    public boolean isEffectiveOn(LocalDate date) {
        return status != PlanStatus.DRAFT && effectiveFrom != null && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    /** Rascunho pode ser descartado (removido); versões publicadas ou aposentadas, não. */
    public void requireDiscardable() {
        requireDraft();
    }

    private void requireDraft() {
        Guard.requireState(status == null || status == PlanStatus.DRAFT, "plan.not_draft",
                "Versão publicada é imutável: crie uma nova versão");
    }

    public String getCode() {
        return code;
    }

    public int getPlanVersion() {
        return planVersion;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public int getPriority() {
        return priority;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getRules() {
        return rules;
    }

    public int getRulesSchemaVersion() {
        return rulesSchemaVersion;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public UUID getRetiredBy() {
        return retiredBy;
    }
}
