package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases;
import br.com.gestorcobranca.payment.domain.model.PaymentSettings;
import br.com.gestorcobranca.payment.domain.repository.PaymentSettingsRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@Transactional
class PaymentSettingsService implements PaymentSettingsUseCases {

    private final PaymentSettingsRepository settings;
    private final PaymentSettingsProvider provider;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    PaymentSettingsService(PaymentSettingsRepository settings, PaymentSettingsProvider provider, AccessScope accessScope,
                           AuditTrail auditTrail) {
        this.settings = settings;
        this.provider = provider;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public SettingsView get() {
        Optional<PaymentSettings> stored = settings.findFirstByOrderByCreatedAtAsc();
        return view(provider.current(), stored.isPresent(), stored.map(PaymentSettings::getUpdatedAt).orElse(null));
    }

    @Override
    public SettingsView update(SettingsCommand command) {
        accessScope.requirePermission(Permission.ORG_SETTINGS_MANAGE);
        accessScope.requireAllCreditors("payment_settings");
        Guard.require(command != null && command.externalReviewThreshold() != null, "payment_settings.required",
                "Parâmetros obrigatórios");
        PaymentSettings.Values values = new PaymentSettings.Values(Money.of(command.externalReviewThreshold()),
                command.pixExpirationSeconds(), command.pixDueValidityDays(), command.reconciliationDateToleranceDays());
        PaymentSettings.Values before = provider.current();
        PaymentSettings entity = settings.findFirstByOrderByCreatedAtAsc().orElse(null);
        if (entity == null) {
            entity = new PaymentSettings(values);
        } else {
            entity.define(values);
        }
        PaymentSettings saved = settings.save(entity);
        auditTrail.record(AuditEntry.of("payment_settings.updated", "payment_settings", saved.getId())
                .change("externalReviewThreshold", before.externalReviewThreshold(), values.externalReviewThreshold())
                .change("pixExpirationSeconds", before.pixExpirationSeconds(), values.pixExpirationSeconds())
                .change("pixDueValidityDays", before.pixDueValidityDays(), values.pixDueValidityDays())
                .change("reconciliationDateToleranceDays", before.reconciliationDateToleranceDays(),
                        values.reconciliationDateToleranceDays()));
        return view(values, true, saved.getUpdatedAt());
    }

    private static SettingsView view(PaymentSettings.Values v, boolean customized, Instant updatedAt) {
        return new SettingsView(v.externalReviewThreshold().amount(), v.pixExpirationSeconds(), v.pixDueValidityDays(),
                v.reconciliationDateToleranceDays(), customized, updatedAt);
    }
}
