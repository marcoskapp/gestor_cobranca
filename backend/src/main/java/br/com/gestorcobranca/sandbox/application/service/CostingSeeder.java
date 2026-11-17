package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.ItemEstimateView;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.SaveInvoiceCommand;
import br.com.gestorcobranca.costing.application.port.in.CostRefreshUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.AssignUserBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateOverheadCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateTariffCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.SaveFxRateCommand;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.shared.security.Actor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custos operacionais de demonstração (valores <b>fictícios</b>) para o painel "Margem por carteira": faixas de custo
 * por cargo, faixa dos usuários de demonstração, tarifas de canal, câmbio mensal e custos fixos; depois, a apuração e
 * as faturas fictícias de canal (conciliação com a fatura, base "Conciliado" do painel). Feito pelo financeiro, pelos
 * casos de uso do costing (mesmas validações e auditoria da operação real).
 * <p>
 * Sem histórico: câmbio dos últimos 3 meses, um custo fixo do mês anterior, apuração dos últimos 60 dias e faturas de
 * telefonia e WhatsApp do mês anterior ("Parcialmente conciliado"). Com o histórico de 12 meses (decisão 1354): faixas
 * para todos os negociadores, câmbio dos 13 meses, quatro custos fixos por mês com regras de rateio diferentes (horas,
 * recuperado, casos trabalhados e um credor), apuração do período inteiro (em blocos de até 90 dias, o limite da
 * reapuração), faturas de todos os itens do 2º ao 6º mês anteriores (a estimativa ± até 6%: "Conciliado") e só
 * telefonia e WhatsApp no mês anterior ("Parcialmente conciliado"). Subir de novo não duplica: com faixas já
 * cadastradas, só reapura.
 */
@SandboxSeedComponent
class CostingSeeder {

    private static final Map<String, String> PERSON_BANDS = Map.of(
            "operador", "Negociador júnior",
            DemoCatalog.SECOND_OPERATOR_KEY, "Negociador pleno",
            "supervisor", "Supervisor de equipe");
    private static final int REFRESH_CHUNK_DAYS = 90;

    private final CostSettingsUseCases settings;
    private final CostRefreshUseCases refresh;
    private final ChannelInvoiceUseCases invoices;
    private final SeedRuntime rt;

    CostingSeeder(CostSettingsUseCases settings, CostRefreshUseCases refresh, ChannelInvoiceUseCases invoices,
                  SeedRuntime rt) {
        this.settings = settings;
        this.refresh = refresh;
        this.invoices = invoices;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        Actor financeiro = seed.actor("financeiro");
        LocalDate start = seed.today.withDayOfYear(1).minusYears(1);
        YearMonth current = YearMonth.from(seed.today);
        rt.optional("cadastros de custo fictícios", () -> {
            if (!rt.as(financeiro, settings::bands).isEmpty()) {
                return;
            }
            rt.asRun(financeiro, () -> {
                settings.createBand(new CreateBandCommand("Negociador trainee", new BigDecimal("31.00"), start));
                settings.createBand(new CreateBandCommand("Negociador júnior", new BigDecimal("38.00"), start));
                settings.createBand(new CreateBandCommand("Negociador pleno", new BigDecimal("46.00"), start));
                settings.createBand(new CreateBandCommand("Negociador sênior", new BigDecimal("54.00"), start));
                settings.createBand(new CreateBandCommand("Supervisor de equipe", new BigDecimal("62.00"), start));
                bands(seed).forEach((person, band) -> settings.assignUserBand(seed.user(person),
                        new AssignUserBandCommand(band, start)));
                settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_UTILITY, new BigDecimal("0.04"),
                        start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_MARKETING, new BigDecimal("0.32"),
                        start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_AUTHENTICATION,
                        new BigDecimal("0.03"), start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.SMS, new BigDecimal("0.09"), start));
                settings.createTariff(new CreateTariffCommand(TariffChannel.EMAIL, new BigDecimal("0.0005"), start));
                int fxMonths = seed.hasHistory() ? DemoHistory.MONTHS + 1 : 3;
                for (int i = fxMonths; i >= 1; i--) {
                    // dólar fictício oscilando entre R$ 5,25 e R$ 5,55
                    BigDecimal usd = new BigDecimal("5.40").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(
                            (i * 3) % 7 - 3)));
                    settings.saveFxRate(new SaveFxRateCommand(current.minusMonths(i), usd));
                }
                if (seed.hasHistory()) {
                    historyOverheads(seed, current);
                } else {
                    settings.createOverhead(new CreateOverheadCommand(current.minusMonths(1),
                            "Licenças e infraestrutura (fictício)", new BigDecimal("4500.00"), OverheadAllocation.HOURS,
                            null));
                    settings.createOverhead(new CreateOverheadCommand(current.minusMonths(1),
                            "Equipe dedicada (fictício)", new BigDecimal("1800.00"), OverheadAllocation.CREDITOR,
                            seed.creditorId(0)));
                }
            });
        });
        if (seed.hasHistory()) {
            // apuração do período inteiro, em blocos (a reapuração aceita até 93 dias por pedido)
            LocalDate yesterday = seed.today.minusDays(1);
            for (LocalDate from = seed.historyStart; !from.isAfter(yesterday); from = from.plusDays(REFRESH_CHUNK_DAYS)) {
                LocalDate f = from;
                LocalDate to = from.plusDays(REFRESH_CHUNK_DAYS - 1).isAfter(yesterday) ? yesterday
                        : from.plusDays(REFRESH_CHUNK_DAYS - 1);
                rt.optional("apuração dos custos " + f + " a " + to, () -> rt.as(financeiro, () -> refresh.refresh(f, to)));
            }
            rt.optional("faturas de canal fictícias", () -> historyInvoices(seed, financeiro, current));
            return;
        }
        rt.optional("apuração dos custos", () -> rt.as(financeiro,
                () -> refresh.refresh(seed.today.minusDays(60), seed.today.minusDays(1))));
        // faturas fictícias de telefonia e WhatsApp do mês anterior (o painel mostra "Parcialmente conciliado")
        YearMonth previous = current.minusMonths(1);
        rt.optional("faturas de canal fictícias", () -> rt.asRun(financeiro, () -> {
            if (!invoices.invoices(previous, previous).isEmpty()) {
                return;
            }
            invoices.save(new SaveInvoiceCommand(previous, ChannelItem.TELEPHONY, new BigDecimal("180.00"),
                    "Operadora de telefonia (fictício)", null));
            invoices.save(new SaveInvoiceCommand(previous, ChannelItem.WHATSAPP, new BigDecimal("95.00"),
                    "Provedor de WhatsApp (fictício)", null));
        }));
    }

    /** Faixa de custo de cada pessoa de demonstração que atende casos. */
    private static Map<String, String> bands(TenantSeed seed) {
        Map<String, String> bands = new LinkedHashMap<>(PERSON_BANDS);
        if (seed.hasHistory()) {
            seed.history.supervisors().forEach(p -> bands.put(p.key(), "Supervisor de equipe"));
            seed.history.negotiators().forEach(n -> bands.put(n.key(), n.band()));
        }
        return bands;
    }

    /**
     * Quatro custos fixos por mês do histórico (e do mês corrente), cada um com uma regra de rateio: licenças por
     * horas, aluguel pelo recuperado, monitoria pelos casos trabalhados e a equipe dedicada ao maior credor.
     */
    private void historyOverheads(TenantSeed seed, YearMonth current) {
        FictitiousData random = new FictitiousData(seed.spec.seed() * 53);
        YearMonth first = YearMonth.from(seed.historyStart);
        java.util.UUID dedicated = seed.creditors.get(seed.history.creditors().getFirst().prefix());
        for (YearMonth month = first; !month.isAfter(current); month = month.plusMonths(1)) {
            settings.createOverhead(new CreateOverheadCommand(month, "Licenças e infraestrutura (fictício)",
                    money(4200 + random.between(0, 700)), OverheadAllocation.HOURS, null));
            settings.createOverhead(new CreateOverheadCommand(month, "Aluguel e facilities (fictício)",
                    money(5600 + random.between(0, 300)), OverheadAllocation.RECOVERED, null));
            settings.createOverhead(new CreateOverheadCommand(month, "Equipe de monitoria e qualidade (fictício)",
                    money(2400 + random.between(0, 400)), OverheadAllocation.CASES, null));
            settings.createOverhead(new CreateOverheadCommand(month, "Equipe dedicada ao Banco Meridional (fictício)",
                    money(3100), OverheadAllocation.CREDITOR, dedicated));
        }
    }

    /**
     * Faturas fictícias: do 6º ao 2º mês anteriores, todos os itens com estimativa (a fatura difere da estimativa em
     * −4% a +6%: base "Conciliado"); no mês anterior, só telefonia e WhatsApp ("Parcialmente conciliado").
     */
    private void historyInvoices(TenantSeed seed, Actor financeiro, YearMonth current) {
        FictitiousData random = new FictitiousData(seed.spec.seed() * 59);
        YearMonth from = current.minusMonths(6);
        YearMonth previous = current.minusMonths(1);
        if (!rt.as(financeiro, () -> invoices.invoices(from, previous)).isEmpty()) {
            return;
        }
        List<ItemEstimateView> estimates = rt.as(financeiro, () -> invoices.estimates(from, previous));
        for (ItemEstimateView estimate : estimates) {
            YearMonth month = YearMonth.parse(estimate.month());
            boolean partialMonth = month.equals(previous);
            if (estimate.estimated() == null || estimate.estimated().signum() <= 0
                    || partialMonth && estimate.item() != ChannelItem.TELEPHONY && estimate.item() != ChannelItem.WHATSAPP) {
                continue;
            }
            BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(random.between(-40, 60), 3));
            BigDecimal amount = estimate.estimated().multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
            rt.optional("fatura " + estimate.item() + " " + month, () -> rt.as(financeiro, () -> invoices.save(
                    new SaveInvoiceCommand(month, estimate.item(), amount, supplier(estimate.item()),
                            "Fatura fictícia do sandbox"))));
        }
    }

    private static String supplier(ChannelItem item) {
        return switch (item) {
            case TELEPHONY -> "Operadora de telefonia (fictício)";
            case WHATSAPP -> "Provedor de WhatsApp (fictício)";
            case SMS -> "Agregador de SMS (fictício)";
            case EMAIL -> "Serviço de e-mail (fictício)";
            case AI -> "Provedor de IA (fictício, convertido em reais)";
        };
    }

    private static BigDecimal money(int whole) {
        return BigDecimal.valueOf(whole * 100L, 2);
    }
}
