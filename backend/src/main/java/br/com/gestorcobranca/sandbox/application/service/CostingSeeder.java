package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.costing.application.port.in.CostRefreshUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.AssignUserBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateOverheadCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateTariffCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.SaveFxRateCommand;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.shared.security.Actor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * Custos operacionais de demonstração (valores <b>fictícios</b>) para o painel "Margem por carteira": faixas de custo
 * por cargo, faixa dos usuários de demonstração, tarifas de canal, câmbio dos últimos meses e um custo fixo do mês
 * anterior rateado por horas; depois, a apuração dos últimos 60 dias. Feito pelo financeiro, pelos casos de uso do
 * costing (mesmas validações e auditoria da operação real). Subir de novo não duplica: com faixas já cadastradas, só
 * reapura.
 */
@SandboxSeedComponent
class CostingSeeder {

    private static final Map<String, String> PERSON_BANDS = Map.of(
            "operador", "Negociador júnior",
            DemoCatalog.SECOND_OPERATOR_KEY, "Negociador pleno",
            "supervisor", "Supervisor de equipe");

    private final CostSettingsUseCases settings;
    private final CostRefreshUseCases refresh;
    private final SeedRuntime rt;

    CostingSeeder(CostSettingsUseCases settings, CostRefreshUseCases refresh, SeedRuntime rt) {
        this.settings = settings;
        this.refresh = refresh;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        Actor financeiro = seed.actor("financeiro");
        LocalDate start = seed.today.withDayOfYear(1).minusYears(1);
        rt.optional("cadastros de custo fictícios", () -> {
            if (!rt.as(financeiro, settings::bands).isEmpty()) {
                return;
            }
            rt.asRun(financeiro, () -> {
                settings.createBand(new CreateBandCommand("Negociador júnior", new BigDecimal("38.00"), start));
                settings.createBand(new CreateBandCommand("Negociador pleno", new BigDecimal("46.00"), start));
                settings.createBand(new CreateBandCommand("Supervisor de equipe", new BigDecimal("62.00"), start));
                PERSON_BANDS.forEach((person, band) -> settings.assignUserBand(seed.user(person),
                        new AssignUserBandCommand(band, start)));
                settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_UTILITY, new BigDecimal("0.04"),
                        start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_MARKETING, new BigDecimal("0.32"),
                        start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_AUTHENTICATION,
                        new BigDecimal("0.03"), start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.SMS, new BigDecimal("0.09"), start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.EMAIL, new BigDecimal("0.0005"), start));
                YearMonth current = YearMonth.from(seed.today);
                for (int i = 3; i >= 1; i--) {
                    settings.saveFxRate(new SaveFxRateCommand(current.minusMonths(i),
                            new BigDecimal("5.40").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(i)))));
                }
                settings.createOverhead(new CreateOverheadCommand(current.minusMonths(1),
                        "Licenças e infraestrutura (fictício)", new BigDecimal("4500.00"), OverheadAllocation.HOURS,
                        null));
                settings.createOverhead(new CreateOverheadCommand(current.minusMonths(1),
                        "Equipe dedicada (fictício)", new BigDecimal("1800.00"), OverheadAllocation.CREDITOR,
                        seed.creditorId(0)));
            });
        });
        rt.optional("apuração dos custos", () -> rt.as(financeiro,
                () -> refresh.refresh(seed.today.minusDays(60), seed.today.minusDays(1))));
    }
}
