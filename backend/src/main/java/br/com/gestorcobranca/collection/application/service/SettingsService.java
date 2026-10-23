package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases;
import br.com.gestorcobranca.collection.domain.model.CollectionSettings;
import br.com.gestorcobranca.collection.domain.model.DefaultTabulations;
import br.com.gestorcobranca.collection.domain.model.Tabulation;
import br.com.gestorcobranca.collection.domain.repository.CollectionSettingsRepository;
import br.com.gestorcobranca.collection.domain.repository.TabulationRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
class SettingsService implements CollectionSettingsUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    private final CollectionSettingsRepository settings;
    private final TabulationRepository tabulations;
    private final SettingsProvider provider;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    SettingsService(CollectionSettingsRepository settings, TabulationRepository tabulations, SettingsProvider provider,
                    AccessScope accessScope, AuditTrail auditTrail) {
        this.settings = settings;
        this.tabulations = tabulations;
        this.provider = provider;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public SettingsView get() {
        Optional<CollectionSettings> stored = settings.findFirstByOrderByCreatedAtAsc();
        CollectionSettings.Values values = provider.current();
        return view(values, stored.isPresent(), stored.map(CollectionSettings::getUpdatedAt).orElse(null));
    }

    @Override
    public SettingsView update(SettingsCommand command) {
        accessScope.requireAllCreditors("collection_settings");
        CollectionSettings.Values values = new CollectionSettings.Values(command.promiseToleranceDays(),
                command.promiseMaxDays(), command.callbackToleranceMinutes(), command.disputeResponseDays());
        CollectionSettings.Values before = provider.current();
        CollectionSettings entity = settings.findFirstByOrderByCreatedAtAsc().orElse(null);
        if (entity == null) {
            entity = new CollectionSettings(values);
        } else {
            entity.define(values);
        }
        CollectionSettings saved = settings.save(entity);
        auditTrail.record(AuditEntry.of("collection_settings.updated", "collection_settings", saved.getId())
                .change("promiseToleranceDays", before.promiseToleranceDays(), values.promiseToleranceDays())
                .change("promiseMaxDays", before.promiseMaxDays(), values.promiseMaxDays())
                .change("callbackToleranceMinutes", before.callbackToleranceMinutes(), values.callbackToleranceMinutes())
                .change("disputeResponseDays", before.disputeResponseDays(), values.disputeResponseDays()));
        return view(values, true, saved.getUpdatedAt());
    }

    @Override
    public int ensureDefaults() {
        int created = 0;
        for (DefaultTabulations.Entry entry : DefaultTabulations.catalog()) {
            if (tabulations.findByCode(entry.code()).isEmpty()) {
                Tabulation saved = tabulations.save(new Tabulation(entry.code(), entry.definition()));
                auditTrail.record(AuditEntry.of("tabulation.created", "tabulation", saved.getId())
                        .created("code", saved.getCode())
                        .meta("origin", "default_catalog"));
                created++;
            }
        }
        if (created > 0) {
            LOG.atInfo().addKeyValue("created", created).log("Catálogo padrão de tabulações semeado na assessoria");
        }
        return created;
    }

    private static SettingsView view(CollectionSettings.Values v, boolean customized, java.time.Instant updatedAt) {
        return new SettingsView(v.promiseToleranceDays(), v.promiseMaxDays(), v.callbackToleranceMinutes(),
                v.disputeResponseDays(), customized, updatedAt);
    }
}
