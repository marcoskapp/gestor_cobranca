package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases;
import br.com.gestorcobranca.legal.domain.model.BudgetEnforcement;
import br.com.gestorcobranca.legal.domain.model.CollectionHoldStage;
import br.com.gestorcobranca.legal.domain.model.ConductModel;
import br.com.gestorcobranca.legal.domain.model.ExpenseAdvancer;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.model.PrescribedTitlePolicy;
import br.com.gestorcobranca.legal.domain.repository.LegalSettingsRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
class LegalSettingsService implements LegalSettingsUseCases {

    private static final String ENTITY = "legal_settings";

    private final LegalSettingsRepository settings;
    private final LegalSettingsProvider provider;
    private final LegalAccess access;
    private final AuditTrail auditTrail;

    LegalSettingsService(LegalSettingsRepository settings, LegalSettingsProvider provider, LegalAccess access,
                         AuditTrail auditTrail) {
        this.settings = settings;
        this.provider = provider;
        this.access = access;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public SettingsView get() {
        Optional<LegalSettings> stored = settings.findFirstByOrderByCreatedAtAsc();
        return view(stored.map(LegalSettings::values).orElseGet(provider::defaults), stored.isPresent());
    }

    @Override
    public SettingsView update(SettingsCommand c) {
        access.requireInternal(ENTITY);
        access.requireAllCreditors(ENTITY);
        Guard.require(c.allowedExpenseAdvancers() != null, "legal_settings.advancers_required",
                "Informe quem pode adiantar custas");
        Set<ExpenseAdvancer> advancers = c.allowedExpenseAdvancers().stream()
                .map(a -> LegalViews.parse(ExpenseAdvancer.class, a, "legal_settings.advancer_invalid", "Quem adianta custas"))
                .collect(Collectors.toSet());
        LegalSettings.Values values = new LegalSettings.Values(
                LegalViews.parse(ConductModel.class, c.conductModel(), "legal_settings.conduct_invalid", "Quem conduz"),
                advancers,
                LegalViews.parse(BudgetEnforcement.class, c.budgetEnforcement(), "legal_settings.budget_invalid",
                        "Controle de orçamento"),
                LegalViews.parse(PrescribedTitlePolicy.class, c.prescribedTitlePolicy(),
                        "legal_settings.prescribed_policy_invalid", "Tratamento de títulos prescritos"),
                LegalViews.parse(CollectionHoldStage.class, c.collectionHoldStage(), "legal_settings.hold_stage_invalid",
                        "Retenção do caso de cobrança"),
                c.deadlineAlertLevels(), c.yearEndRecess(), c.removalDeadlineBusinessDays(),
                c.homologationDeadlineBusinessDays(), c.creditBureauNoticeDays(), c.prescriptionWarningDays(),
                c.referralCooldownDays());
        LegalSettings.Values before = provider.current();
        LegalSettings entity = settings.findFirstByOrderByCreatedAtAsc().orElse(null);
        if (entity == null) {
            entity = settings.save(new LegalSettings(values));
        } else if (entity.apply(values)) {
            settings.save(entity);
        }
        AuditEntry audit = AuditEntry.of("legal_settings.updated", ENTITY, entity.getId());
        audit.change("conductModel", before.conductModel(), values.conductModel())
                .change("allowedExpenseAdvancers", names(before.allowedExpenseAdvancers()),
                        names(values.allowedExpenseAdvancers()))
                .change("budgetEnforcement", before.budgetEnforcement(), values.budgetEnforcement())
                .change("prescribedTitlePolicy", before.prescribedTitlePolicy(), values.prescribedTitlePolicy())
                .change("collectionHoldStage", before.collectionHoldStage(), values.collectionHoldStage())
                .change("deadlineAlertLevels", before.deadlineAlertLevels().toString(), values.deadlineAlertLevels().toString())
                .change("yearEndRecess", before.yearEndRecess(), values.yearEndRecess())
                .change("removalDeadlineBusinessDays", before.removalDeadlineBusinessDays(),
                        values.removalDeadlineBusinessDays())
                .change("homologationDeadlineBusinessDays", before.homologationDeadlineBusinessDays(),
                        values.homologationDeadlineBusinessDays())
                .change("creditBureauNoticeDays", before.creditBureauNoticeDays(), values.creditBureauNoticeDays())
                .change("prescriptionWarningDays", before.prescriptionWarningDays(), values.prescriptionWarningDays())
                .change("referralCooldownDays", before.referralCooldownDays(), values.referralCooldownDays());
        if (!audit.after().isEmpty()) {
            auditTrail.record(audit);
        }
        return view(values, true);
    }

    private static String names(Set<ExpenseAdvancer> set) {
        return set.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static SettingsView view(LegalSettings.Values v, boolean customized) {
        return new SettingsView(v.conductModel().name(),
                v.allowedExpenseAdvancers().stream().map(Enum::name).collect(Collectors.toSet()),
                v.budgetEnforcement().name(), v.prescribedTitlePolicy().name(), v.collectionHoldStage().name(),
                v.deadlineAlertLevels(), v.yearEndRecess(), v.removalDeadlineBusinessDays(),
                v.homologationDeadlineBusinessDays(), v.creditBureauNoticeDays(), v.prescriptionWarningDays(),
                v.referralCooldownDays(), customized);
    }
}
