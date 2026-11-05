package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Limite da assessoria para aplicar uma <b>redução</b> manual do valor do título sem segunda pessoa (RB06). Enquanto não
 * gravado, vale {@link #DEFAULT_THRESHOLD}. Zero: toda correção passa por aprovação. Aumentos sempre passam.
 */
@Entity
@Table(name = "amount_adjustment_settings", schema = "debt")
public class AmountAdjustmentSettings extends TenantScopedEntity {

    public static final Money DEFAULT_THRESHOLD = Money.of("200.00");
    public static final Money MAX_THRESHOLD = Money.of("100000.00");

    @Column(name = "approval_threshold", nullable = false, precision = 19, scale = 2)
    private Money approvalThreshold;

    protected AmountAdjustmentSettings() {
    }

    public AmountAdjustmentSettings(Money approvalThreshold, UUID updatedBy) {
        change(approvalThreshold, updatedBy);
    }

    public final void change(Money threshold, UUID by) {
        Guard.require(threshold != null && !threshold.isNegative() && !threshold.isGreaterThan(MAX_THRESHOLD),
                "title_amount.threshold_invalid", "Limite entre R$ 0,00 e R$ 100.000,00");
        this.approvalThreshold = threshold;
    }

    public Money getApprovalThreshold() {
        return approvalThreshold;
    }
}
