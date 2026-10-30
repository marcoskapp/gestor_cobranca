package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Versão das condições de cobrança das integrações (API pública) num contrato de prestação de serviço: dia de
 * fechamento do consumo, mensalidade, franquias e preços do excedente por chamada, por MB recebido, por MB enviado e
 * por registro. Cada alteração é uma nova versão gravada uma única vez (append-only, também por gatilho no banco).
 * <p>
 * <b>Não retroatividade:</b> uma versão vale para os ciclos de consumo que começam depois do seu registro
 * ({@link #getRegisteredAt()}); o ciclo em curso continua com a versão anterior. MB = 1.048.576 bytes (binário).
 */
@Entity
@Table(name = "contract_integration_pricing", schema = "organization")
public class ContractIntegrationPricing extends TenantScopedEntity {

    public static final int MAX_CLOSING_DAY = 31;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "pricing_version", nullable = false, updatable = false)
    private int pricingVersion;

    /** Dia do mês (1–31) em que o ciclo de consumo fecha; meses mais curtos usam o último dia. */
    @Column(name = "usage_closing_day", nullable = false, updatable = false)
    private int usageClosingDay;

    @Column(name = "monthly_fee", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal monthlyFee;

    @Column(name = "included_requests", nullable = false, updatable = false)
    private long includedRequests;

    @Column(name = "price_per_request", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal pricePerRequest;

    @Column(name = "included_mb_in", nullable = false, updatable = false)
    private long includedMbIn;

    @Column(name = "price_per_mb_in", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal pricePerMbIn;

    @Column(name = "included_mb_out", nullable = false, updatable = false)
    private long includedMbOut;

    @Column(name = "price_per_mb_out", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal pricePerMbOut;

    @Column(name = "included_records", nullable = false, updatable = false)
    private long includedRecords;

    @Column(name = "price_per_record", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal pricePerRecord;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "registered_by", updatable = false)
    private UUID registeredBy;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    protected ContractIntegrationPricing() {
    }

    /** Condições informadas (valores monetários com até 2 casas na mensalidade e até 6 nos preços unitários). */
    public record Terms(int usageClosingDay, BigDecimal monthlyFee, long includedRequests, BigDecimal pricePerRequest,
                        long includedMbIn, BigDecimal pricePerMbIn, long includedMbOut, BigDecimal pricePerMbOut,
                        long includedRecords, BigDecimal pricePerRecord) {
    }

    public ContractIntegrationPricing(ServiceContract contract, int pricingVersion, Terms terms, Instant registeredAt,
                                      UUID registeredBy, String reason) {
        Guard.require(contract != null, "contract.required", "Contrato obrigatório");
        Guard.requireState(contract.getStatus() == ContractStatus.ACTIVE, "contract.not_active", "Contrato não está ativo");
        Guard.require(pricingVersion >= 1, "contract_pricing.version_invalid", "Versão inválida");
        Guard.require(terms != null, "contract_pricing.terms_required", "Condições obrigatórias");
        Guard.require(terms.usageClosingDay() >= 1 && terms.usageClosingDay() <= MAX_CLOSING_DAY,
                "contract_pricing.closing_day_invalid", "Dia de fechamento do consumo deve estar entre 1 e 31");
        Guard.require(reason == null || reason.strip().length() <= 500, "contract_pricing.reason_too_long",
                "Motivo com até 500 caracteres");
        this.contractId = contract.getId();
        this.creditorId = contract.getCreditorId();
        this.pricingVersion = pricingVersion;
        this.usageClosingDay = terms.usageClosingDay();
        this.monthlyFee = money(terms.monthlyFee(), "mensalidade", 2);
        this.includedRequests = allowance(terms.includedRequests());
        this.pricePerRequest = money(terms.pricePerRequest(), "preço por chamada", 6);
        this.includedMbIn = allowance(terms.includedMbIn());
        this.pricePerMbIn = money(terms.pricePerMbIn(), "preço por MB recebido", 6);
        this.includedMbOut = allowance(terms.includedMbOut());
        this.pricePerMbOut = money(terms.pricePerMbOut(), "preço por MB enviado", 6);
        this.includedRecords = allowance(terms.includedRecords());
        this.pricePerRecord = money(terms.pricePerRecord(), "preço por registro", 6);
        this.registeredAt = registeredAt;
        this.registeredBy = registeredBy;
        this.reason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    private static BigDecimal money(BigDecimal value, String field, int scale) {
        Guard.require(value != null && value.signum() >= 0, "contract_pricing.value_invalid",
                "Valor de %s deve ser zero ou positivo".formatted(field));
        Guard.require(value.stripTrailingZeros().scale() <= scale, "contract_pricing.scale_invalid",
                "%s com no máximo %d casas decimais".formatted(field, scale));
        Guard.require(value.precision() - value.scale() <= 13, "contract_pricing.value_too_large",
                "Valor muito alto: " + field);
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    private static long allowance(long value) {
        Guard.require(value >= 0, "contract_pricing.allowance_invalid", "Franquias devem ser zero ou positivas");
        return value;
    }

    public UUID getContractId() {
        return contractId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public int getPricingVersion() {
        return pricingVersion;
    }

    public int getUsageClosingDay() {
        return usageClosingDay;
    }

    public BigDecimal getMonthlyFee() {
        return monthlyFee;
    }

    public long getIncludedRequests() {
        return includedRequests;
    }

    public BigDecimal getPricePerRequest() {
        return pricePerRequest;
    }

    public long getIncludedMbIn() {
        return includedMbIn;
    }

    public BigDecimal getPricePerMbIn() {
        return pricePerMbIn;
    }

    public long getIncludedMbOut() {
        return includedMbOut;
    }

    public BigDecimal getPricePerMbOut() {
        return pricePerMbOut;
    }

    public long getIncludedRecords() {
        return includedRecords;
    }

    public BigDecimal getPricePerRecord() {
        return pricePerRecord;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public UUID getRegisteredBy() {
        return registeredBy;
    }

    public String getReason() {
        return reason;
    }
}
