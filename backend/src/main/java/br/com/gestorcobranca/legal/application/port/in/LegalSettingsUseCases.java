package br.com.gestorcobranca.legal.application.port.in;

import java.util.List;
import java.util.Set;

/**
 * Parâmetros do jurídico da assessoria, inclusive as decisões pendentes da issue #14 (quem
 * conduz, quem adianta custas, dívidas prescritas) com padrões conservadores. Leitura com
 * {@code LEGAL_VIEW}; alteração exige {@code LEGAL_MANAGE}, acesso a todos os credores e usuário
 * interno (não membro de escritório parceiro). Alterações são auditadas.
 */
public interface LegalSettingsUseCases {

    SettingsView get();

    SettingsView update(SettingsCommand command);

    /**
     * @param conductModel            INTERNAL_ONLY, LAW_FIRMS_ONLY ou BOTH
     * @param allowedExpenseAdvancers CREDITOR, AGENCY e/ou LAW_FIRM
     * @param budgetEnforcement       BLOCK ou WARN
     * @param prescribedTitlePolicy   EXCLUDE ou FLAG
     * @param collectionHoldStage     REVIEW ou AUTHORIZATION
     */
    record SettingsCommand(String conductModel, Set<String> allowedExpenseAdvancers, String budgetEnforcement,
                           String prescribedTitlePolicy, String collectionHoldStage, List<Integer> deadlineAlertLevels,
                           boolean yearEndRecess, int removalDeadlineBusinessDays, int homologationDeadlineBusinessDays,
                           int creditBureauNoticeDays, int prescriptionWarningDays, int referralCooldownDays) {
    }

    /** @param customized falso enquanto valem os padrões da aplicação */
    record SettingsView(String conductModel, Set<String> allowedExpenseAdvancers, String budgetEnforcement,
                        String prescribedTitlePolicy, String collectionHoldStage, List<Integer> deadlineAlertLevels,
                        boolean yearEndRecess, int removalDeadlineBusinessDays, int homologationDeadlineBusinessDays,
                        int creditBureauNoticeDays, int prescriptionWarningDays, int referralCooldownDays,
                        boolean customized) {
    }
}
