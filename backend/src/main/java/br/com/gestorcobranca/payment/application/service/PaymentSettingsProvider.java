package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.domain.model.PaymentSettings;
import br.com.gestorcobranca.payment.domain.repository.PaymentSettingsRepository;
import org.springframework.stereotype.Component;

/** Parâmetros vigentes da assessoria corrente: os gravados ou, sem registro, os padrões da plataforma. */
@Component
class PaymentSettingsProvider {

    private final PaymentSettingsRepository settings;
    private final PaymentProperties properties;

    PaymentSettingsProvider(PaymentSettingsRepository settings, PaymentProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    PaymentSettings.Values current() {
        return settings.findFirstByOrderByCreatedAtAsc().map(PaymentSettings::values)
                .orElseGet(() -> properties.defaults().values());
    }
}
