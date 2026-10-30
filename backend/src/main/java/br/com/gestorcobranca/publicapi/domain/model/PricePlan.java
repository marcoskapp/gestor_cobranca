package br.com.gestorcobranca.publicapi.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Versão de uma tabela de preços da API pública. A assessoria tem uma tabela padrão (sem credor) e pode ter tabelas
 * por credor; o extrato de um mês usa a versão publicada mais recente com vigência até o início do mês (a do credor
 * tem precedência sobre a padrão). Versão publicada é imutável (também por gatilho no banco); mudança de preço é uma
 * nova versão com nova vigência.
 */
@Entity
@Table(name = "price_plan", schema = "publicapi")
public class PricePlan extends TenantScopedEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 40)
    private String code;

    @Column(name = "plan_version", nullable = false, updatable = false)
    private int planVersion;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /** Primeiro dia do mês a partir do qual a versão vale. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Embedded
    private PriceTerms terms;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PricePlanStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    protected PricePlan() {
    }

    public PricePlan(String code, int planVersion, UUID creditorId, String name, LocalDate effectiveFrom, PriceTerms terms) {
        Guard.require(code != null && code.strip().matches("[A-Z0-9_-]{2,40}"), "price_plan.code_invalid",
                "Código com 2 a 40 letras maiúsculas, dígitos, _ ou -");
        Guard.require(planVersion >= 1, "price_plan.version_invalid", "Versão inválida");
        this.code = code.strip();
        this.planVersion = planVersion;
        this.creditorId = creditorId;
        this.currency = "BRL";
        this.status = PricePlanStatus.DRAFT;
        update(name, effectiveFrom, terms);
    }

    /** Edita o rascunho. */
    public void update(String name, LocalDate effectiveFrom, PriceTerms terms) {
        Guard.requireState(status == PricePlanStatus.DRAFT, "price_plan.published", "Versão publicada não muda");
        Guard.require(name != null && !name.isBlank() && name.strip().length() <= 100, "price_plan.name_required",
                "Nome obrigatório (até 100 caracteres)");
        Guard.require(effectiveFrom != null && effectiveFrom.getDayOfMonth() == 1, "price_plan.effective_from_invalid",
                "A vigência começa no primeiro dia de um mês");
        Guard.require(terms != null, "price_plan.terms_required", "Condições obrigatórias");
        this.name = name.strip();
        this.effectiveFrom = effectiveFrom;
        this.terms = terms;
    }

    /**
     * Publica a versão. A vigência não pode começar num mês já faturado nem antes do mês corrente (preço não muda
     * retroativamente).
     */
    public void publish(UUID by, Instant now, LocalDate currentMonthStart) {
        Guard.requireState(status == PricePlanStatus.DRAFT, "price_plan.published", "Versão já publicada");
        Guard.require(!effectiveFrom.isBefore(currentMonthStart), "price_plan.retroactive",
                "A vigência não pode começar antes do mês corrente");
        this.status = PricePlanStatus.PUBLISHED;
        this.publishedAt = now;
        this.publishedBy = by;
    }

    public String getCode() {
        return code;
    }

    public int getPlanVersion() {
        return planVersion;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public PriceTerms getTerms() {
        return terms;
    }

    public PricePlanStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }
}
