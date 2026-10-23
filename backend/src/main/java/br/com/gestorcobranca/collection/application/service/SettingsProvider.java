package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.domain.model.CollectionSettings;
import br.com.gestorcobranca.collection.domain.repository.CollectionSettingsRepository;
import org.springframework.stereotype.Component;

/** Parâmetros vigentes da assessoria corrente: os gravados ou, sem registro, os padrões da plataforma. */
@Component
class SettingsProvider {

    private final CollectionSettingsRepository settings;
    private final CollectionProperties properties;

    SettingsProvider(CollectionSettingsRepository settings, CollectionProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    CollectionSettings.Values current() {
        return settings.findFirstByOrderByCreatedAtAsc().map(CollectionSettings::values)
                .orElseGet(() -> properties.defaults().values());
    }
}
