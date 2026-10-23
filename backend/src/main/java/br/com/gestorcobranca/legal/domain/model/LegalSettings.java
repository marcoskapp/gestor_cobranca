package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parâmetros do jurídico da assessoria (um registro por tenant; sem registro valem os padrões
 * conservadores de {@code app.legal.defaults}). Concentra as decisões pendentes da issue #14 —
 * quem conduz ({@link ConductModel}), quem adianta custas ({@code allowedExpenseAdvancers}) e
 * dívidas prescritas ({@link PrescribedTitlePolicy}) — e os parâmetros operacionais (retenção do
 * caso no collection, níveis de alerta de prazo, recesso forense, prazos de baixa e de
 * comunicação prévia, janelas de prescrição e de nova indicação).
 */
@Entity
@Table(name = "legal_settings", schema = "legal")
public class LegalSettings extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "conduct_model", nullable = false, length = 20)
    private ConductModel conductModel;

    @Column(name = "allowed_expense_advancers", nullable = false, length = 60)
    private String allowedExpenseAdvancers;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_enforcement", nullable = false, length = 10)
    private BudgetEnforcement budgetEnforcement;

    @Enumerated(EnumType.STRING)
    @Column(name = "prescribed_title_policy", nullable = false, length = 10)
    private PrescribedTitlePolicy prescribedTitlePolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_hold_stage", nullable = false, length = 20)
    private CollectionHoldStage collectionHoldStage;

    @Column(name = "deadline_alert_levels", nullable = false, length = 40)
    private String deadlineAlertLevels;

    @Column(name = "year_end_recess", nullable = false)
    private boolean yearEndRecess;

    @Column(name = "removal_deadline_business_days", nullable = false)
    private int removalDeadlineBusinessDays;

    @Column(name = "homologation_deadline_business_days", nullable = false)
    private int homologationDeadlineBusinessDays;

    @Column(name = "credit_bureau_notice_days", nullable = false)
    private int creditBureauNoticeDays;

    @Column(name = "prescription_warning_days", nullable = false)
    private int prescriptionWarningDays;

    @Column(name = "referral_cooldown_days", nullable = false)
    private int referralCooldownDays;

    protected LegalSettings() {
    }

    public LegalSettings(Values values) {
        apply(values);
    }

    /**
     * @param deadlineAlertLevels   dias úteis restantes em que se alerta (decrescentes, ex.: 5, 2, 0)
     * @param removalDeadlineBusinessDays prazo para baixar protesto/negativação após o pagamento (Súmula 548/STJ: 5 dias úteis)
     * @param creditBureauNoticeDays antecedência mínima da comunicação prévia à negativação (CDC art. 43 §2º)
     * @param prescriptionWarningDays janela de atenção de prescrição na triagem
     * @param referralCooldownDays  dias sem nova indicação automática após o encerramento de um caso jurídico do mesmo caso
     */
    public record Values(ConductModel conductModel, Set<ExpenseAdvancer> allowedExpenseAdvancers,
                         BudgetEnforcement budgetEnforcement, PrescribedTitlePolicy prescribedTitlePolicy,
                         CollectionHoldStage collectionHoldStage, List<Integer> deadlineAlertLevels, boolean yearEndRecess,
                         int removalDeadlineBusinessDays, int homologationDeadlineBusinessDays, int creditBureauNoticeDays,
                         int prescriptionWarningDays, int referralCooldownDays) {

        public Values {
            Guard.require(conductModel != null && budgetEnforcement != null && prescribedTitlePolicy != null
                    && collectionHoldStage != null, "legal_settings.required", "Parâmetros obrigatórios");
            Guard.require(allowedExpenseAdvancers != null && !allowedExpenseAdvancers.isEmpty(),
                    "legal_settings.advancers_required", "Informe quem pode adiantar custas");
            Guard.require(deadlineAlertLevels != null && !deadlineAlertLevels.isEmpty() && deadlineAlertLevels.size() <= 5
                            && deadlineAlertLevels.stream().allMatch(l -> l != null && l >= 0 && l <= 30),
                    "legal_settings.alert_levels_invalid", "Níveis de alerta: de 1 a 5 valores entre 0 e 30 dias úteis");
            Guard.require(between(removalDeadlineBusinessDays, 1, 30) && between(homologationDeadlineBusinessDays, 1, 60)
                            && between(creditBureauNoticeDays, 1, 60) && between(prescriptionWarningDays, 1, 1825)
                            && between(referralCooldownDays, 0, 1825),
                    "legal_settings.value_invalid", "Parâmetro fora do intervalo permitido");
            allowedExpenseAdvancers = Set.copyOf(allowedExpenseAdvancers);
            deadlineAlertLevels = deadlineAlertLevels.stream().distinct().sorted((a, b) -> b - a).toList();
        }

        private static boolean between(int v, int min, int max) {
            return v >= min && v <= max;
        }
    }

    /** @return true se algo mudou */
    public boolean apply(Values v) {
        boolean changed = !v.equals(values());
        this.conductModel = v.conductModel();
        this.allowedExpenseAdvancers = v.allowedExpenseAdvancers().stream().map(Enum::name).sorted()
                .collect(Collectors.joining(","));
        this.budgetEnforcement = v.budgetEnforcement();
        this.prescribedTitlePolicy = v.prescribedTitlePolicy();
        this.collectionHoldStage = v.collectionHoldStage();
        this.deadlineAlertLevels = v.deadlineAlertLevels().stream().map(String::valueOf).collect(Collectors.joining(","));
        this.yearEndRecess = v.yearEndRecess();
        this.removalDeadlineBusinessDays = v.removalDeadlineBusinessDays();
        this.homologationDeadlineBusinessDays = v.homologationDeadlineBusinessDays();
        this.creditBureauNoticeDays = v.creditBureauNoticeDays();
        this.prescriptionWarningDays = v.prescriptionWarningDays();
        this.referralCooldownDays = v.referralCooldownDays();
        return changed;
    }

    public Values values() {
        if (conductModel == null) {
            return null;
        }
        Set<ExpenseAdvancer> advancers = EnumSet.noneOf(ExpenseAdvancer.class);
        Arrays.stream(allowedExpenseAdvancers.split(",")).map(ExpenseAdvancer::valueOf).forEach(advancers::add);
        List<Integer> levels = Arrays.stream(deadlineAlertLevels.split(",")).map(Integer::valueOf).toList();
        return new Values(conductModel, advancers, budgetEnforcement, prescribedTitlePolicy, collectionHoldStage, levels,
                yearEndRecess, removalDeadlineBusinessDays, homologationDeadlineBusinessDays, creditBureauNoticeDays,
                prescriptionWarningDays, referralCooldownDays);
    }
}
