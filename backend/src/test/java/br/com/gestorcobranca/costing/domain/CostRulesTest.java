package br.com.gestorcobranca.costing.domain;

import br.com.gestorcobranca.costing.domain.model.ChannelTariff;
import br.com.gestorcobranca.costing.domain.model.CostBand;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarning;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.FxRate;
import br.com.gestorcobranca.costing.domain.model.Overhead;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.costing.domain.model.Usage.AiUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.CallUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.DirectTime;
import br.com.gestorcobranca.costing.domain.model.Usage.LoggedTime;
import br.com.gestorcobranca.costing.domain.model.Usage.MessageCount;
import br.com.gestorcobranca.costing.domain.model.Usage.TemplateSend;
import br.com.gestorcobranca.costing.domain.model.UserCostBand;
import br.com.gestorcobranca.costing.domain.service.CostAccumulator;
import br.com.gestorcobranca.costing.domain.service.HumanCost;
import br.com.gestorcobranca.costing.domain.service.OverheadAllocator;
import br.com.gestorcobranca.costing.domain.service.RateBook;
import br.com.gestorcobranca.costing.domain.service.UsageCost;
import br.com.gestorcobranca.costing.domain.service.WhatsAppConversations;
import br.com.gestorcobranca.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras da apuração de custos (desenho margem-por-carteira §4 e §9). */
class CostRulesTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    static final UUID ANA = UUID.randomUUID();
    static final UUID BRUNO = UUID.randomUUID();
    static final UUID C1 = UUID.randomUUID();
    static final UUID C2 = UUID.randomUUID();
    static final UUID P1 = UUID.randomUUID();
    static final UUID P2 = UUID.randomUUID();

    private static RateBook rates(List<FxRate> fx) {
        return new RateBook(
                List.of(new CostBand("Negociador júnior", new BigDecimal("36.00"), LocalDate.of(2026, 1, 1))),
                List.of(new UserCostBand(ANA, "Negociador júnior", LocalDate.of(2026, 1, 1))),
                List.of(new ChannelTariff(TariffChannel.WHATSAPP_UTILITY, new BigDecimal("0.040000"),
                                LocalDate.of(2026, 1, 1)),
                        new ChannelTariff(TariffChannel.SMS, new BigDecimal("0.080000"), LocalDate.of(2026, 1, 1))),
                fx);
    }

    private static Map<String, CostFact> byKey(List<CostFact> facts) {
        Map<String, CostFact> m = new LinkedHashMap<>();
        facts.forEach(f -> m.put(f.category() + "|" + f.creditorId() + "|" + f.portfolioId(), f));
        return m;
    }

    @Test
    void tempoDiretoPeloValorHoraEOciosidadeRateadaPeloTempoDiretoDaPessoaNoDia() {
        CostAccumulator acc = new CostAccumulator();
        // Ana: 1 h no credor 1 e 3 h no credor 2; 6 h logadas → 2 h ociosas: 0,5 h para C1 e 1,5 h para C2
        HumanCost.apply(List.of(new DirectTime(DAY, ANA, C1, P1, 3600), new DirectTime(DAY, ANA, C2, P2, 3 * 3600)),
                List.of(new LoggedTime(DAY, ANA, 6 * 3600)), rates(List.of()), acc);
        Map<String, CostFact> f = byKey(acc.facts());
        assertThat(f.get("HUMAN_DIRECT|" + C1 + "|" + P1).amount()).isEqualByComparingTo("36");
        assertThat(f.get("HUMAN_DIRECT|" + C2 + "|" + P2).amount()).isEqualByComparingTo("108");
        assertThat(f.get("HUMAN_IDLE|" + C1 + "|" + P1).quantity()).isEqualByComparingTo("0.5");
        assertThat(f.get("HUMAN_IDLE|" + C1 + "|" + P1).amount()).isEqualByComparingTo("18");
        assertThat(f.get("HUMAN_IDLE|" + C2 + "|" + P2).amount()).isEqualByComparingTo("54");
        assertThat(acc.warnings()).isEmpty();
    }

    @Test
    void logadoMenorQueODiretoNaoGeraOciosidadeNegativa() {
        CostAccumulator acc = new CostAccumulator();
        HumanCost.apply(List.of(new DirectTime(DAY, ANA, C1, P1, 7200)), List.of(new LoggedTime(DAY, ANA, 3600)),
                rates(List.of()), acc);
        assertThat(acc.facts()).extracting(CostFact::category).containsExactly(CostCategory.HUMAN_DIRECT);
    }

    @Test
    void logadoSemTempoDiretoViraOciosidadeSemCredorESemFaixaGeraAvisoDeHoras() {
        CostAccumulator acc = new CostAccumulator();
        HumanCost.apply(List.of(new DirectTime(DAY, BRUNO, C1, P1, 1800)),
                List.of(new LoggedTime(DAY, ANA, 2 * 3600), new LoggedTime(DAY, BRUNO, 1800)), rates(List.of()), acc);
        Map<String, CostFact> f = byKey(acc.facts());
        CostFact idle = f.get("HUMAN_IDLE|null|null");
        assertThat(idle.amount()).isEqualByComparingTo("72");
        assertThat(idle.quantity()).isEqualByComparingTo("2");
        // Bruno sem faixa: meia hora com custo zero e aviso no credor
        assertThat(f.get("HUMAN_DIRECT|" + C1 + "|" + P1).amount()).isZero();
        assertThat(acc.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(CostWarningCode.HOURS_WITHOUT_BAND);
            assertThat(w.creditorId()).isEqualTo(C1);
            assertThat(w.quantity()).isEqualByComparingTo("0.5");
        });
    }

    @Test
    void faixaDaPessoaApontaParaONomeEOValorVemDaVigenciaDaFaixaNoDia() {
        CostBand old = new CostBand("Supervisor", new BigDecimal("60.00"), LocalDate.of(2026, 1, 1));
        old.endOn(LocalDate.of(2026, 9, 30));
        CostBand current = new CostBand("Supervisor", new BigDecimal("66.00"), LocalDate.of(2026, 10, 1));
        UserCostBand ub = new UserCostBand(ANA, "Supervisor", LocalDate.of(2026, 1, 1));
        RateBook book = new RateBook(List.of(old, current), List.of(ub), List.of(), List.of());
        assertThat(book.hourlyCost(ANA, LocalDate.of(2026, 9, 30))).contains(new BigDecimal("60.00"));
        assertThat(book.hourlyCost(ANA, LocalDate.of(2026, 10, 1))).contains(new BigDecimal("66.00"));
        assertThat(book.hourlyCost(ANA, LocalDate.of(2025, 12, 31))).isEmpty();
        assertThat(book.hourlyCost(BRUNO, DAY)).isEmpty();
    }

    @Test
    void minutoTarifadoArredondaParaCimaPorChamada() {
        assertThat(UsageCost.billedMinutes(0)).isZero();
        assertThat(UsageCost.billedMinutes(1)).isEqualTo(1);
        assertThat(UsageCost.billedMinutes(60)).isEqualTo(1);
        assertThat(UsageCost.billedMinutes(61)).isEqualTo(2);
        CostAccumulator acc = new CostAccumulator();
        UsageCost.telephony(new CallUsage(DAY, C1, P1, UUID.randomUUID(), new BigDecimal("0.12"), 61, 3), acc);
        UsageCost.telephony(new CallUsage(DAY, C1, P1, UUID.randomUUID(), null, 30, 2), acc);
        CostFact f = acc.facts().getFirst();
        assertThat(f.quantity()).isEqualByComparingTo("8");
        assertThat(f.amount()).isEqualByComparingTo("0.72");
        assertThat(acc.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(CostWarningCode.MISSING_TARIFF);
            assertThat(w.quantity()).isEqualByComparingTo("2");
        });
    }

    @Test
    void whatsAppCobraUmaVezPorJanelaDe24HorasPorConversaECategoria() {
        UUID conversation = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-10-15T12:00:00Z");
        WhatsAppConversations w = new WhatsAppConversations();
        assertThat(w.accept(send(conversation, "UTILITY", t0))).contains(TariffChannel.WHATSAPP_UTILITY);
        assertThat(w.accept(send(conversation, "UTILITY", t0.plusSeconds(23 * 3600)))).isEmpty();
        assertThat(w.accept(send(conversation, "MARKETING", t0.plusSeconds(3600))))
                .contains(TariffChannel.WHATSAPP_MARKETING);
        assertThat(w.accept(send(conversation, "UTILITY", t0.plusSeconds(24 * 3600))))
                .contains(TariffChannel.WHATSAPP_UTILITY);
        assertThat(w.accept(send(UUID.randomUUID(), "UTILITY", t0.plusSeconds(60))))
                .contains(TariffChannel.WHATSAPP_UTILITY);
        assertThat(w.accept(send(conversation, null, t0))).isEmpty();
    }

    private static TemplateSend send(UUID conversation, String category, Instant at) {
        return new TemplateSend(conversation, category, at, DAY, C1, P1);
    }

    @Test
    void mensagensSemTarifaEntramComCustoZeroEAviso() {
        CostAccumulator acc = new CostAccumulator();
        UsageCost.messages(new MessageCount(DAY, C1, P1, TariffChannel.SMS, 10), rates(List.of()), acc);
        UsageCost.messages(new MessageCount(DAY, C1, P1, TariffChannel.EMAIL, 5), rates(List.of()), acc);
        CostFact f = acc.facts().getFirst();
        assertThat(f.quantity()).isEqualByComparingTo("15");
        assertThat(f.amount()).isEqualByComparingTo("0.8");
        assertThat(acc.warnings()).singleElement().extracting(CostWarning::quantity)
                .satisfies(q -> assertThat(q).isEqualByComparingTo("5"));
    }

    @Test
    void iaUsaOCambioDoMesOuOUltimoAnteriorOuZeroComAviso() {
        FxRate september = new FxRate(YearMonth.of(2026, 9), new BigDecimal("5.40"));
        RateBook book = rates(List.of(september));
        CostAccumulator acc = new CostAccumulator();
        UsageCost.ai(new AiUsage(LocalDate.of(2026, 9, 10), C1, P1, "USD", new BigDecimal("2")), book, acc);
        UsageCost.ai(new AiUsage(LocalDate.of(2026, 10, 10), C1, P1, "USD", new BigDecimal("1")), book, acc);
        UsageCost.ai(new AiUsage(LocalDate.of(2026, 8, 10), C1, P1, "USD", new BigDecimal("1")), book, acc);
        UsageCost.ai(new AiUsage(LocalDate.of(2026, 8, 10), C2, P2, "BRL", new BigDecimal("3")), book, acc);
        Map<LocalDate, BigDecimal> amounts = new LinkedHashMap<>();
        acc.facts().stream().filter(f -> C1.equals(f.creditorId())).forEach(f -> amounts.put(f.day(), f.amount()));
        assertThat(amounts.get(LocalDate.of(2026, 9, 10))).isEqualByComparingTo("10.80");
        assertThat(amounts.get(LocalDate.of(2026, 10, 10))).isEqualByComparingTo("5.40");
        assertThat(amounts.get(LocalDate.of(2026, 8, 10))).isZero();
        assertThat(acc.facts().stream().filter(f -> C2.equals(f.creditorId())).findFirst().orElseThrow().amount())
                .isEqualByComparingTo("3");
        assertThat(acc.warnings()).extracting(CostWarning::code)
                .containsExactlyInAnyOrder(CostWarningCode.MISSING_FX_RATE, CostWarningCode.NO_FX_RATE);
        assertThat(book.fx(YearMonth.of(2026, 9)).status()).isEqualTo(RateBook.FxStatus.OWN);
    }

    @Test
    void rateioDeFixosPorHorasSomaExatamenteOValor() {
        Overhead rent = new Overhead(YearMonth.of(2026, 10), "Aluguel", new BigDecimal("1000.00"), null, null);
        assertThat(rent.getAllocation()).isEqualTo(OverheadAllocation.HOURS);
        Map<CostKey, BigDecimal> basis = new LinkedHashMap<>();
        basis.put(new CostKey(DAY, C1, P1), new BigDecimal("1"));
        basis.put(new CostKey(DAY, C2, P2), new BigDecimal("2"));
        basis.put(new CostKey(DAY, null, null), new BigDecimal("5"));
        basis.put(new CostKey(LocalDate.of(2026, 11, 1), C1, P1), new BigDecimal("9"));
        CostAccumulator acc = new CostAccumulator();
        OverheadAllocator.allocate(rent, basis, acc);
        Map<String, CostFact> f = byKey(acc.facts());
        assertThat(f).hasSize(2);
        assertThat(f.get("OVERHEAD|" + C1 + "|" + P1).amount()).isEqualByComparingTo("333.333333");
        assertThat(f.get("OVERHEAD|" + C2 + "|" + P2).amount()).isEqualByComparingTo("666.666667");
        assertThat(acc.facts().stream().map(CostFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000");
    }

    @Test
    void fixoDiretoAoCredorPeloDiasDoMesEFixoSemBaseFicaSemCredorComAviso() {
        CostAccumulator acc = new CostAccumulator();
        OverheadAllocator.allocate(new Overhead(YearMonth.of(2026, 2), "Equipe dedicada", new BigDecimal("100.00"),
                OverheadAllocation.CREDITOR, C1), Map.of(), acc);
        assertThat(acc.facts()).hasSize(28).allMatch(f -> C1.equals(f.creditorId()) && f.portfolioId() == null);
        assertThat(acc.facts().stream().map(CostFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100");

        CostAccumulator none = new CostAccumulator();
        OverheadAllocator.allocate(new Overhead(YearMonth.of(2026, 2), "Licenças", new BigDecimal("50.00"),
                OverheadAllocation.CASES, null), Map.of(), none);
        assertThat(none.facts()).singleElement().satisfies(f -> {
            assertThat(f.creditorId()).isNull();
            assertThat(f.day()).isEqualTo(LocalDate.of(2026, 2, 1));
        });
        assertThat(none.warnings()).extracting(CostWarning::code).containsExactly(CostWarningCode.OVERHEAD_WITHOUT_BASIS);
    }

    @Test
    void custoFixoDiretoExigeCredorEOsDemaisNaoAceitamCredor() {
        assertThatThrownBy(() -> new Overhead(YearMonth.of(2026, 2), "x", BigDecimal.TEN, OverheadAllocation.CREDITOR,
                null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Overhead(YearMonth.of(2026, 2), "x", BigDecimal.TEN, OverheadAllocation.HOURS,
                C1)).isInstanceOf(DomainException.class);
    }

    @Test
    void vigenciaSoAntecipaOFimENaoAceitaFimAntesDoInicio() {
        CostBand band = new CostBand("  Negociador   pleno ", new BigDecimal("40"), LocalDate.of(2026, 1, 1));
        assertThat(band.getName()).isEqualTo("Negociador pleno");
        assertThatThrownBy(() -> band.endOn(LocalDate.of(2025, 12, 31))).isInstanceOf(DomainException.class);
        band.endOn(LocalDate.of(2026, 6, 30));
        assertThatThrownBy(() -> band.endOn(LocalDate.of(2026, 7, 31))).isInstanceOf(DomainException.class);
        assertThat(band.overlaps(LocalDate.of(2026, 7, 1), null)).isFalse();
        assertThat(band.overlaps(LocalDate.of(2026, 6, 30), null)).isTrue();
        assertThatThrownBy(() -> new CostBand("x", BigDecimal.ZERO, DAY)).isInstanceOf(DomainException.class);
        assertThat(Optional.of(band).map(CostBand::getHourlyCost)).contains(new BigDecimal("40.00"));
    }
}
