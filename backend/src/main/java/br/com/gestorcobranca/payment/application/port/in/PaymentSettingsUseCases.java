package br.com.gestorcobranca.payment.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Parâmetros de pagamentos da assessoria (leitura: {@code PAYMENT_VIEW}; alteração:
 * {@code ORG_SETTINGS_MANAGE} e acesso a todos os credores). O limite de revisão vale para pagamentos
 * informados depois da alteração.
 */
public interface PaymentSettingsUseCases {

    SettingsView get();

    SettingsView update(SettingsCommand command);

    record SettingsCommand(BigDecimal externalReviewThreshold, int pixExpirationSeconds, int pixDueValidityDays,
                           int reconciliationDateToleranceDays) {
    }

    /** @param customized falso enquanto valem os padrões da plataforma */
    record SettingsView(BigDecimal externalReviewThreshold, int pixExpirationSeconds, int pixDueValidityDays,
                        int reconciliationDateToleranceDays, boolean customized, Instant updatedAt) {
    }
}
