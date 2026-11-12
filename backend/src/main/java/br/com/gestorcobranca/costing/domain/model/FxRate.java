package br.com.gestorcobranca.costing.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Câmbio mensal USD→BRL informado pela gestão para converter o custo de IA (desenho §3.3). Um por mês; a correção do
 * valor fica na auditoria. A consulta automática da PTAX é integração externa (fase 2, issue).
 */
@Entity
@Table(name = "fx_rate", schema = "costing")
public class FxRate extends TenantScopedEntity {

    public static final BigDecimal MAX_RATE = new BigDecimal("999999.999999");

    @Column(name = "month", nullable = false, updatable = false)
    private LocalDate month;

    @Column(name = "usd_brl", nullable = false)
    private BigDecimal usdBrl;

    protected FxRate() {
    }

    public FxRate(YearMonth month, BigDecimal usdBrl) {
        Guard.requireNonNull(month, "costing.fx_month_required", "Informe o mês");
        this.month = month.atDay(1);
        change(usdBrl);
    }

    public void change(BigDecimal value) {
        Guard.require(value != null && value.signum() > 0 && value.compareTo(MAX_RATE) <= 0,
                "costing.fx_rate_invalid", "Informe um câmbio maior que zero");
        Guard.require(value.stripTrailingZeros().scale() <= 6, "costing.fx_rate_scale",
                "O câmbio tem no máximo seis casas decimais");
        this.usdBrl = value.setScale(6, RoundingMode.UNNECESSARY);
    }

    public YearMonth getMonth() {
        return YearMonth.from(month);
    }

    public BigDecimal getUsdBrl() {
        return usdBrl;
    }
}
