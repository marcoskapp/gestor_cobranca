package br.com.gestorcobranca.payment.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Parâmetros de pagamentos da assessoria (leitura: {@code PAYMENT_VIEW}; alteração:
 * {@code ORG_SETTINGS_MANAGE} e acesso a todos os credores; só o limite de revisão:
 * {@code PAYMENT_REGISTER_EXTERNAL}). O limite de revisão vale para pagamentos informados depois da alteração.
 */
public interface PaymentSettingsUseCases {

    SettingsView get();

    SettingsView update(SettingsCommand command);

    /**
     * Só o limite da segunda conferência, para quem informa pagamento externo ({@code PAYMENT_REGISTER_EXTERNAL})
     * antecipar na tela que o valor vai para aprovação de outra pessoa. Nada mais dos parâmetros; quem decide a
     * revisão continua sendo o registro no servidor (limites acumulados incluídos).
     */
    ExternalReviewLimitView externalReviewLimit();

    record SettingsCommand(BigDecimal externalReviewThreshold, int pixExpirationSeconds, int pixDueValidityDays,
                           int reconciliationDateToleranceDays) {
    }

    /** @param customized falso enquanto valem os padrões da plataforma */
    record SettingsView(BigDecimal externalReviewThreshold, int pixExpirationSeconds, int pixDueValidityDays,
                        int reconciliationDateToleranceDays, boolean customized, Instant updatedAt) {
    }

    /** @param externalReviewThreshold pagamento externo acima deste valor vai para a aprovação de outra pessoa */
    record ExternalReviewLimitView(BigDecimal externalReviewThreshold) {
    }
}
