package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.LegalProperties;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.repository.LegalSettingsRepository;
import org.springframework.stereotype.Component;

/** Parâmetros vigentes do tenant corrente: os gravados pela assessoria ou os padrões conservadores. */
@Component
class LegalSettingsProvider {

    private final LegalSettingsRepository settings;
    private final LegalProperties properties;

    LegalSettingsProvider(LegalSettingsRepository settings, LegalProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    LegalSettings.Values current() {
        return settings.findFirstByOrderByCreatedAtAsc().map(LegalSettings::values)
                .orElseGet(() -> properties.defaults().toValues());
    }

    LegalSettings.Values defaults() {
        return properties.defaults().toValues();
    }
}
