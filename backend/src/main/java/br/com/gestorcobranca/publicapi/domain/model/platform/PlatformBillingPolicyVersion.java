package br.com.gestorcobranca.publicapi.domain.model.platform;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versão das condições da política de cobrança da plataforma para uma assessoria, gravada uma única vez
 * (append-only, também por gatilho no banco). <b>Não retroatividade:</b> vale para os ciclos que começam depois do
 * registro; a <b>primeira</b> versão vale já no ciclo em andamento (o ciclo inteiro), como o primeiro cadastro de
 * condições no contrato do credor.
 */
@Entity
@Table(name = "platform_billing_policy_version", schema = "publicapi")
public class PlatformBillingPolicyVersion extends TenantScopedEntity {

    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "closing_day", nullable = false, updatable = false)
    private int closingDay;

    @Column(name = "monthly_fee", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal monthlyFee;

    @Column(name = "included_requests", nullable = false, updatable = false)
    private long includedRequests;

    @Column(name = "price_per_request", updatable = false, precision = 19, scale = 6)
    private BigDecimal pricePerRequest;

    /** Faixas de volume das chamadas (JSON); {@code []} = preço único. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_tiers", nullable = false, updatable = false)
    private String requestTiers;

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

    /** Usuário da plataforma que registrou a versão (não é usuário desta assessoria). */
    @Column(name = "registered_by", updatable = false)
    private UUID registeredBy;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    protected PlatformBillingPolicyVersion() {
    }

    /** @param tiersJson faixas de volume já serializadas (as mesmas de {@code terms.requestTiers()}) */
    public PlatformBillingPolicyVersion(UUID policyId, int policyVersion, PlatformPriceTerms terms, String tiersJson,
                                        Instant registeredAt, UUID registeredBy, String reason) {
        Guard.require(policyId != null && terms != null && tiersJson != null, "platform_policy.terms_required",
                "Condições obrigatórias");
        Guard.require(policyVersion >= 1, "platform_policy.version_invalid", "Versão inválida");
        Guard.require(reason == null || reason.strip().length() <= 500, "platform_policy.reason_too_long",
                "Motivo com até 500 caracteres");
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.closingDay = terms.closingDay();
        this.monthlyFee = terms.monthlyFee();
        this.includedRequests = terms.includedRequests();
        this.pricePerRequest = terms.pricePerRequest();
        this.requestTiers = tiersJson;
        this.includedMbIn = terms.includedMbIn();
        this.pricePerMbIn = terms.pricePerMbIn();
        this.includedMbOut = terms.includedMbOut();
        this.pricePerMbOut = terms.pricePerMbOut();
        this.includedRecords = terms.includedRecords();
        this.pricePerRecord = terms.pricePerRecord();
        this.registeredAt = registeredAt;
        this.registeredBy = registeredBy;
        this.reason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** Condições da versão, com as faixas desserializadas por quem chama. */
    public PlatformPriceTerms terms(List<VolumeTier> tiers) {
        return new PlatformPriceTerms(closingDay, monthlyFee, includedRequests, pricePerRequest, tiers, includedMbIn,
                pricePerMbIn, includedMbOut, pricePerMbOut, includedRecords, pricePerRecord);
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public int getClosingDay() {
        return closingDay;
    }

    public BigDecimal getMonthlyFee() {
        return monthlyFee;
    }

    public String getRequestTiers() {
        return requestTiers;
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
