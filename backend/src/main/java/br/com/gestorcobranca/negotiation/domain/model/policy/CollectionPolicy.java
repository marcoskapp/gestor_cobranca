package br.com.gestorcobranca.negotiation.domain.model.policy;

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
 * Versão da política de cobrança e negociação de um credor (§1). Cada credor tem uma linha do
 * tempo de versões ({@code policyVersion} crescente). Rascunhos são editáveis; versões publicadas
 * são imutáveis (termos, credor, versão e início de vigência — reforçado por gatilho no banco) e
 * só podem ter a vigência encerrada, por nova versão publicada ou por aposentadoria.
 * <p>
 * A vigência é por dia ({@code effectiveFrom} inclusive, {@code effectiveTo} inclusive ou
 * aberta); nunca começa no passado, para que propostas e simulações já registradas continuem
 * reproduzíveis pela versão que usaram (RB03).
 */
@Entity
@Table(name = "collection_policy", schema = "negotiation")
public class CollectionPolicy extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "terms", nullable = false)
    private String terms;

    @Column(name = "terms_schema_version", nullable = false)
    private int termsSchemaVersion;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "retired_by")
    private UUID retiredBy;

    protected CollectionPolicy() {
    }

    /** Novo rascunho (primeira versão do credor ou nova versão a partir de uma existente). */
    public CollectionPolicy(UUID creditorId, int policyVersion, String name, String description, String termsJson,
                            int termsSchemaVersion) {
        Guard.require(creditorId != null, "policy.creditor_required", "Credor obrigatório");
        Guard.require(policyVersion > 0, "policy.version_invalid", "Versão inválida");
        this.creditorId = creditorId;
        this.policyVersion = policyVersion;
        this.status = PolicyStatus.DRAFT;
        describe(name, description);
        defineTerms(termsJson, termsSchemaVersion);
    }

    public void describe(String name, String description) {
        requireDraft();
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 120, "policy.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(description == null || description.length() <= 1000, "policy.description_too_long",
                "Descrição até 1000 caracteres");
        this.name = name.trim();
        this.description = description;
    }

    /** @param termsJson termos já validados e serializados pela aplicação */
    public void defineTerms(String termsJson, int schemaVersion) {
        requireDraft();
        Guard.require(termsJson != null && !termsJson.isBlank(), "policy.terms_required", "Termos obrigatórios");
        this.terms = termsJson;
        this.termsSchemaVersion = schemaVersion;
    }

    /** Publica a versão a partir de {@code effectiveFrom} (hoje ou futuro). */
    public void publish(LocalDate effectiveFrom, LocalDate today, UUID actorId, Instant now) {
        requireDraft();
        Guard.require(effectiveFrom != null, "policy.effective_from_required", "Informe o início de vigência");
        Guard.require(!effectiveFrom.isBefore(today), "policy.effective_from_retroactive",
                "A vigência não pode começar no passado: o histórico de políticas é imutável");
        this.status = PolicyStatus.PUBLISHED;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = null;
        this.publishedAt = now;
        this.publishedBy = actorId;
    }

    /**
     * Encerra a vigência porque outra versão passa a valer em {@code nextStart}. Uma versão
     * agendada que ainda não começou fica sem vigência ({@code effectiveTo = effectiveFrom − 1}).
     *
     * @return true se a vigência mudou
     */
    public boolean supersede(LocalDate nextStart) {
        Guard.requireState(status != PolicyStatus.DRAFT, "policy.not_published", "Versão não publicada");
        LocalDate end = nextStart.isAfter(effectiveFrom) ? nextStart.minusDays(1) : effectiveFrom.minusDays(1);
        if (effectiveTo == null || effectiveTo.isAfter(end)) {
            this.effectiveTo = end;
            return true;
        }
        return false;
    }

    /**
     * Aposenta a versão com vigência até {@code effectiveTo} (inclusive). Encerrar "ontem" retira a
     * política imediatamente.
     */
    public void retire(LocalDate effectiveTo, LocalDate today, UUID actorId, Instant now) {
        Guard.requireState(status == PolicyStatus.PUBLISHED, "policy.not_published",
                "Somente versão publicada pode ser aposentada");
        Guard.require(effectiveTo != null, "policy.effective_to_required", "Informe o fim de vigência");
        Guard.require(!effectiveTo.isBefore(today.minusDays(1)), "policy.effective_to_retroactive",
                "A vigência não pode terminar antes de ontem");
        Guard.require(!effectiveTo.isBefore(effectiveFrom.minusDays(1)), "policy.effective_to_invalid",
                "Fim de vigência anterior ao início");
        if (this.effectiveTo == null || effectiveTo.isBefore(this.effectiveTo)) {
            this.effectiveTo = effectiveTo;
        }
        this.status = PolicyStatus.RETIRED;
        this.retiredAt = now;
        this.retiredBy = actorId;
    }

    public boolean isEffectiveOn(LocalDate date) {
        return status != PolicyStatus.DRAFT && effectiveFrom != null && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    private void requireDraft() {
        Guard.requireState(status == null || status == PolicyStatus.DRAFT, "policy.not_draft",
                "Versão publicada é imutável: crie uma nova versão");
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getTerms() {
        return terms;
    }

    public int getTermsSchemaVersion() {
        return termsSchemaVersion;
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
