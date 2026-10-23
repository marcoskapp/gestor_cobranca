package br.com.gestorcobranca.negotiation.domain.model.index;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Variação mensal de um índice numa competência (% no mês, com sinal: deflação é negativa).
 * Correções de valor são permitidas e auditadas; cálculos já registrados guardam a memória com
 * o fator usado, por isso continuam reproduzíveis.
 */
@Entity
@Table(name = "monetary_index_rate", schema = "negotiation")
public class MonetaryIndexRate extends TenantScopedEntity {

    public static final int SCALE = 4;
    private static final BigDecimal LIMIT = new BigDecimal("100");

    @Column(name = "index_id", nullable = false, updatable = false)
    private UUID indexId;

    /** Primeiro dia do mês de competência. */
    @Column(name = "competence", nullable = false, updatable = false)
    private LocalDate competence;

    @Column(name = "rate_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal ratePercent;

    protected MonetaryIndexRate() {
    }

    public MonetaryIndexRate(UUID indexId, YearMonth competence, BigDecimal ratePercent) {
        Guard.require(indexId != null && competence != null, "index.rate_invalid", "Índice e competência obrigatórios");
        this.indexId = indexId;
        this.competence = competence.atDay(1);
        changeRate(ratePercent);
    }

    /** @return true se o valor mudou */
    public boolean changeRate(BigDecimal ratePercent) {
        Guard.require(ratePercent != null && ratePercent.abs().compareTo(LIMIT) < 0, "index.rate_invalid",
                "Variação mensal deve estar entre -100% e 100%");
        BigDecimal value = ratePercent.setScale(SCALE, RoundingMode.HALF_EVEN);
        boolean changed = this.ratePercent == null || this.ratePercent.compareTo(value) != 0;
        this.ratePercent = value;
        return changed;
    }

    public UUID getIndexId() {
        return indexId;
    }

    public YearMonth getCompetence() {
        return YearMonth.from(competence);
    }

    public BigDecimal getRatePercent() {
        return ratePercent;
    }
}
