package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Parâmetros de pagamentos da assessoria. Enquanto a assessoria não grava os seus, valem os padrões
 * de {@code app.payment.defaults}. O limite de revisão é fixado no pagamento quando ele é informado.
 */
@Entity
@Table(name = "settings", schema = "payment")
public class PaymentSettings extends TenantScopedEntity {

    @Column(name = "external_review_threshold", nullable = false)
    private Money externalReviewThreshold;

    @Column(name = "pix_expiration_seconds", nullable = false)
    private int pixExpirationSeconds;

    @Column(name = "pix_due_validity_days", nullable = false)
    private int pixDueValidityDays;

    @Column(name = "reconciliation_date_tolerance_days", nullable = false)
    private int reconciliationDateToleranceDays;

    protected PaymentSettings() {
    }

    public PaymentSettings(Values values) {
        define(values);
    }

    /**
     * @param externalReviewThreshold         pagamento externo acima deste valor exige aprovação de outra pessoa
     *                                        com {@code PAYMENT_REVIEW}; até ele é confirmado no registro
     * @param pixExpirationSeconds            validade do Pix imediato (cob), 300 s a 30 dias
     * @param pixDueValidityDays              dias de validade após o vencimento do Pix com vencimento (cobv), 0 a 60
     * @param reconciliationDateToleranceDays diferença máxima de datas na conciliação por parcela/título, 0 a 15
     */
    public record Values(Money externalReviewThreshold, int pixExpirationSeconds, int pixDueValidityDays,
                         int reconciliationDateToleranceDays) {

        public Values {
            Guard.require(externalReviewThreshold != null && !externalReviewThreshold.isNegative(),
                    "payment_settings.threshold_invalid", "Limite de revisão deve ser zero ou positivo");
            Guard.require(pixExpirationSeconds >= 300 && pixExpirationSeconds <= 2_592_000,
                    "payment_settings.pix_expiration_invalid", "Validade do Pix imediato entre 300 s e 30 dias");
            Guard.require(pixDueValidityDays >= 0 && pixDueValidityDays <= 60, "payment_settings.pix_due_validity_invalid",
                    "Validade após o vencimento entre 0 e 60 dias");
            Guard.require(reconciliationDateToleranceDays >= 0 && reconciliationDateToleranceDays <= 15,
                    "payment_settings.date_tolerance_invalid", "Tolerância de datas entre 0 e 15 dias");
        }
    }

    public void define(Values values) {
        Guard.require(values != null, "payment_settings.required", "Parâmetros obrigatórios");
        this.externalReviewThreshold = values.externalReviewThreshold();
        this.pixExpirationSeconds = values.pixExpirationSeconds();
        this.pixDueValidityDays = values.pixDueValidityDays();
        this.reconciliationDateToleranceDays = values.reconciliationDateToleranceDays();
    }

    public Values values() {
        return new Values(externalReviewThreshold, pixExpirationSeconds, pixDueValidityDays, reconciliationDateToleranceDays);
    }
}
