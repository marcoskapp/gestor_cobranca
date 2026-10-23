package br.com.gestorcobranca.legal.application;

import br.com.gestorcobranca.legal.domain.model.BudgetEnforcement;
import br.com.gestorcobranca.legal.domain.model.CollectionHoldStage;
import br.com.gestorcobranca.legal.domain.model.ConductModel;
import br.com.gestorcobranca.legal.domain.model.ExpenseAdvancer;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.model.PrescribedTitlePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Parâmetros do módulo legal ({@code app.legal.*}).
 *
 * @param defaults    padrões conservadores dos parâmetros da assessoria enquanto ela não grava os seus
 *                    ({@code PUT /api/v1/legal/settings})
 * @param dailyCron   varredura diária (elegibilidade, prazos, captura automática), no fuso de operação
 * @param batchSize   itens por transação nas varreduras
 * @param maxReferralsPerRun limite de indicações automáticas por assessoria e execução (proteção contra regra ampla demais)
 * @param providers   adaptador ativo de cada porta de integração ({@code manual} até a aprovação das integrações)
 * @param documents   anexos
 */
@ConfigurationProperties(prefix = "app.legal")
public record LegalProperties(Defaults defaults, String dailyCron, Integer batchSize, Integer maxReferralsPerRun,
                              Providers providers, Documents documents) {

    public LegalProperties {
        defaults = defaults == null ? new Defaults(null, null, null, null, null, null, null, null, null, null, null, null)
                : defaults;
        dailyCron = dailyCron == null ? "0 40 5 * * *" : dailyCron;
        batchSize = batchSize == null ? 200 : batchSize;
        maxReferralsPerRun = maxReferralsPerRun == null ? 500 : maxReferralsPerRun;
        providers = providers == null ? new Providers(null, null, null, null, null, null) : providers;
        documents = documents == null ? new Documents(null) : documents;
    }

    /** Ver {@link LegalSettings.Values}. */
    public record Defaults(ConductModel conductModel, Set<ExpenseAdvancer> allowedExpenseAdvancers,
                           BudgetEnforcement budgetEnforcement, PrescribedTitlePolicy prescribedTitlePolicy,
                           CollectionHoldStage collectionHoldStage, List<Integer> deadlineAlertLevels,
                           Boolean yearEndRecess, Integer removalDeadlineBusinessDays,
                           Integer homologationDeadlineBusinessDays, Integer creditBureauNoticeDays,
                           Integer prescriptionWarningDays, Integer referralCooldownDays) {

        public LegalSettings.Values toValues() {
            return new LegalSettings.Values(
                    conductModel == null ? ConductModel.INTERNAL_ONLY : conductModel,
                    allowedExpenseAdvancers == null || allowedExpenseAdvancers.isEmpty()
                            ? Set.of(ExpenseAdvancer.CREDITOR) : allowedExpenseAdvancers,
                    budgetEnforcement == null ? BudgetEnforcement.BLOCK : budgetEnforcement,
                    prescribedTitlePolicy == null ? PrescribedTitlePolicy.EXCLUDE : prescribedTitlePolicy,
                    collectionHoldStage == null ? CollectionHoldStage.AUTHORIZATION : collectionHoldStage,
                    deadlineAlertLevels == null || deadlineAlertLevels.isEmpty() ? List.of(5, 2, 0) : deadlineAlertLevels,
                    yearEndRecess == null || yearEndRecess,
                    removalDeadlineBusinessDays == null ? 5 : removalDeadlineBusinessDays,
                    homologationDeadlineBusinessDays == null ? 5 : homologationDeadlineBusinessDays,
                    creditBureauNoticeDays == null ? 10 : creditBureauNoticeDays,
                    prescriptionWarningDays == null ? 180 : prescriptionWarningDays,
                    referralCooldownDays == null ? 180 : referralCooldownDays);
        }
    }

    /**
     * Código do adaptador de cada porta ({@code manual} = lançamento manual pelo usuário). Um
     * adaptador real é habilitado pelo seu código (ex.: {@code court-data: datajud}) — ver
     * docs/design/juridico.md §B.4.
     */
    public record Providers(String courtData, String publications, String protest, String creditBureau,
                            String realEstateRegistry, String vehicleLien) {

        public Providers {
            courtData = courtData == null ? "manual" : courtData;
            publications = publications == null ? "manual" : publications;
            protest = protest == null ? "manual" : protest;
            creditBureau = creditBureau == null ? "manual" : creditBureau;
            realEstateRegistry = realEstateRegistry == null ? "manual" : realEstateRegistry;
            vehicleLien = vehicleLien == null ? "manual" : vehicleLien;
        }
    }

    /** @param maxSizeBytes tamanho máximo de um documento */
    public record Documents(Long maxSizeBytes) {

        public Documents {
            maxSizeBytes = maxSizeBytes == null ? 20L * 1024 * 1024 : maxSizeBytes;
        }
    }
}
