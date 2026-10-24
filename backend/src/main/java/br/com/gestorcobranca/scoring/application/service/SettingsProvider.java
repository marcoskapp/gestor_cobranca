package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.ScoringProperties;
import br.com.gestorcobranca.scoring.application.port.out.ScoringLocks;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.ScoringSettingsRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Parâmetros da assessoria corrente, criados com os padrões na primeira leitura (sob lock). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class SettingsProvider {

    private final ScoringSettingsRepository settings;
    private final ScoringLocks locks;
    private final ScoringProperties properties;

    SettingsProvider(ScoringSettingsRepository settings, ScoringLocks locks, ScoringProperties properties) {
        this.settings = settings;
        this.locks = locks;
        this.properties = properties;
    }

    ScoringSettings current() {
        Optional<ScoringSettings> found = settings.findCurrent();
        if (found.isPresent()) {
            return found.get();
        }
        locks.lockTenant("settings");
        return settings.findCurrent().orElseGet(() -> settings.save(new ScoringSettings(properties.defaults().toParameters())));
    }

    /** Parâmetros já criados, sem criar (leituras em transação somente leitura). */
    Optional<ScoringSettings> existing() {
        return settings.findCurrent();
    }

    /** Sem criar: base legal registrada? (consumidores de eventos em assessorias sem score) */
    boolean enabled() {
        return settings.findCurrent().map(ScoringSettings::legalBasisRegistered).orElse(false);
    }
}
