package br.com.gestorcobranca.publicapi.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * Condições de preço de uma versão da tabela: mensalidade fixa, franquias mensais e preços unitários do excedente.
 * Megabyte = 1.000.000 bytes (unidade decimal, como nas faturas de nuvem). Preços unitários com até 6 casas; valores
 * cobrados arredondados a centavos (HALF_EVEN) só no fim do cálculo.
 */
@Embeddable
public class PriceTerms {

    @Column(name = "monthly_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyFee;

    @Column(name = "included_requests", nullable = false)
    private long includedRequests;

    @Column(name = "price_per_request", nullable = false, precision = 19, scale = 6)
    private BigDecimal pricePerRequest;

    @Column(name = "included_mb_in", nullable = false)
    private long includedMbIn;

    @Column(name = "price_per_mb_in", nullable = false, precision = 19, scale = 6)
    private BigDecimal pricePerMbIn;

    @Column(name = "included_mb_out", nullable = false)
    private long includedMbOut;

    @Column(name = "price_per_mb_out", nullable = false, precision = 19, scale = 6)
    private BigDecimal pricePerMbOut;

    @Column(name = "included_records", nullable = false)
    private long includedRecords;

    @Column(name = "price_per_record", nullable = false, precision = 19, scale = 6)
    private BigDecimal pricePerRecord;

    protected PriceTerms() {
    }

    public PriceTerms(BigDecimal monthlyFee, long includedRequests, BigDecimal pricePerRequest, long includedMbIn,
                      BigDecimal pricePerMbIn, long includedMbOut, BigDecimal pricePerMbOut, long includedRecords,
                      BigDecimal pricePerRecord) {
        this.monthlyFee = nonNegative(monthlyFee, "monthly_fee", 2);
        this.includedRequests = nonNegative(includedRequests);
        this.pricePerRequest = nonNegative(pricePerRequest, "price_per_request", 6);
        this.includedMbIn = nonNegative(includedMbIn);
        this.pricePerMbIn = nonNegative(pricePerMbIn, "price_per_mb_in", 6);
        this.includedMbOut = nonNegative(includedMbOut);
        this.pricePerMbOut = nonNegative(pricePerMbOut, "price_per_mb_out", 6);
        this.includedRecords = nonNegative(includedRecords);
        this.pricePerRecord = nonNegative(pricePerRecord, "price_per_record", 6);
    }

    private static BigDecimal nonNegative(BigDecimal value, String field, int scale) {
        Guard.require(value != null && value.signum() >= 0, "price_plan.value_invalid",
                "Valor de %s deve ser zero ou positivo".formatted(field));
        Guard.require(value.stripTrailingZeros().scale() <= scale, "price_plan.scale_invalid",
                "%s com no máximo %d casas decimais".formatted(field, scale));
        Guard.require(value.precision() - value.scale() <= 13, "price_plan.value_too_large", "Valor muito alto: " + field);
        return value.setScale(scale, java.math.RoundingMode.UNNECESSARY);
    }

    private static long nonNegative(long value) {
        Guard.require(value >= 0, "price_plan.allowance_invalid", "Franquias devem ser zero ou positivas");
        return value;
    }

    public BigDecimal monthlyFee() {
        return monthlyFee;
    }

    public long includedRequests() {
        return includedRequests;
    }

    public BigDecimal pricePerRequest() {
        return pricePerRequest;
    }

    public long includedMbIn() {
        return includedMbIn;
    }

    public BigDecimal pricePerMbIn() {
        return pricePerMbIn;
    }

    public long includedMbOut() {
        return includedMbOut;
    }

    public BigDecimal pricePerMbOut() {
        return pricePerMbOut;
    }

    public long includedRecords() {
        return includedRecords;
    }

    public BigDecimal pricePerRecord() {
        return pricePerRecord;
    }
}
