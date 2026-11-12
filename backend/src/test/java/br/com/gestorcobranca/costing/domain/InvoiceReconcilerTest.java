package br.com.gestorcobranca.costing.domain;

import br.com.gestorcobranca.costing.domain.model.ChannelInvoice;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.ItemEstimate;
import br.com.gestorcobranca.costing.domain.model.ReconciliationStatus;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.costing.domain.service.CostAccumulator;
import br.com.gestorcobranca.costing.domain.service.InvoiceReconciler;
import br.com.gestorcobranca.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Conciliação do custo de canal com a fatura real (desenho margem-por-carteira §3.5 e §13). */
class InvoiceReconcilerTest {

    static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    static final UUID C1 = UUID.randomUUID();
    static final UUID C2 = UUID.randomUUID();
    static final UUID P1 = UUID.randomUUID();

    /** Linhas (chave, valor estimado, quantidade). */
    private static Map<CostKey, ItemEstimate> estimate(Object... rows) {
        Map<CostKey, ItemEstimate> m = new LinkedHashMap<>();
        for (int i = 0; i < rows.length; i += 3) {
            m.put((CostKey) rows[i], new ItemEstimate(new BigDecimal((String) rows[i + 1]),
                    new BigDecimal((String) rows[i + 2])));
        }
        return m;
    }

    private static CostKey key(int day, UUID creditor, UUID portfolio) {
        return new CostKey(LocalDate.of(2026, 10, day), creditor, portfolio);
    }

    private static BigDecimal total(List<CostFact> facts) {
        return facts.stream().map(CostFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static CostFact fact(List<CostFact> facts, CostKey k) {
        return facts.stream().filter(f -> f.key().equals(k)).findFirst().orElseThrow();
    }

    @Test
    void diferencaRateadaNaProporcaoDaEstimativaInclusiveSemCredor() {
        CostAccumulator acc = new CostAccumulator();
        // estimativa R$ 100 (60 + 30 + 10 sem credor); fatura R$ 120 → ajuste de R$ 20
        BigDecimal adjustment = InvoiceReconciler.reconcile(OCTOBER, ChannelItem.TELEPHONY, new BigDecimal("120.00"),
                estimate(key(1, C1, P1), "60", "600", key(2, C2, null), "30", "300",
                        key(3, null, null), "10", "100"), acc);
        List<CostFact> facts = acc.facts();
        assertThat(adjustment).isEqualByComparingTo("20");
        assertThat(total(facts)).isEqualByComparingTo("20");
        assertThat(fact(facts, key(1, C1, P1)).amount()).isEqualByComparingTo("12");
        assertThat(fact(facts, key(2, C2, null)).amount()).isEqualByComparingTo("6");
        assertThat(fact(facts, key(3, null, null)).amount()).isEqualByComparingTo("2");
        // fato do ajuste: categoria e item do canal, em reais, sem quantidade (o uso está na estimativa)
        assertThat(facts).allSatisfy(f -> {
            assertThat(f.category()).isEqualTo(CostCategory.TELEPHONY);
            assertThat(f.item()).isEqualTo(ChannelItem.TELEPHONY);
            assertThat(f.unit()).isEqualTo(CostFact.Unit.BRL);
            assertThat(f.quantity()).isZero();
        });
        assertThat(acc.warnings()).isEmpty();
    }

    @Test
    void faturaMenorQueAEstimativaGeraAjusteNegativoESomaExataComArredondamentoNaMaiorBase() {
        CostAccumulator acc = new CostAccumulator();
        // três linhas iguais e uma maior: 10 ÷ 3 não fecha em 6 casas; a sobra vai para a maior base (dia 4)
        BigDecimal adjustment = InvoiceReconciler.reconcile(OCTOBER, ChannelItem.WHATSAPP, new BigDecimal("0.00"),
                estimate(key(1, C1, P1), "1", "25", key(2, C1, P1), "1", "25", key(3, C1, P1), "1",
                        "25", key(4, C2, null), "7", "175"), acc);
        assertThat(adjustment).isEqualByComparingTo("-10");
        List<CostFact> facts = acc.facts();
        assertThat(total(facts)).isEqualByComparingTo("-10");
        // fatura zero: estimativa + ajuste = 0 em cada linha (com a diferença de arredondamento na maior)
        assertThat(fact(facts, key(1, C1, P1)).amount()).isEqualByComparingTo("-1");
        assertThat(fact(facts, key(4, C2, null)).amount()).isEqualByComparingTo("-7");
        assertThat(facts).allSatisfy(f -> assertThat(f.amount().scale()).isEqualTo(6));

        CostAccumulator thirds = new CostAccumulator();
        InvoiceReconciler.reconcile(OCTOBER, ChannelItem.SMS, new BigDecimal("13.00"), estimate(
                key(1, C1, P1), "1", "1", key(2, C1, P1), "1", "1", key(3, C2, null), "2", "2"),
                thirds);
        // ajuste 9: 2,25 + 2,25 + 4,50 — e 10 ÷ 3 em outro caso; a soma sempre fecha
        assertThat(total(thirds.facts())).isEqualByComparingTo("9");
        CostAccumulator odd = new CostAccumulator();
        InvoiceReconciler.reconcile(OCTOBER, ChannelItem.SMS, new BigDecimal("13.00"), estimate(
                key(1, C1, P1), "1", "1", key(2, C1, P1), "1", "1", key(3, C2, null), "1", "1"), odd);
        assertThat(total(odd.facts())).isEqualByComparingTo("10");
        assertThat(odd.facts().getFirst().amount()).isEqualByComparingTo("3.333334");
        assertThat(odd.facts().get(1).amount()).isEqualByComparingTo("3.333333");
    }

    @Test
    void estimativaZeroRateiaPelaQuantidadeDeUso() {
        CostAccumulator acc = new CostAccumulator();
        // sem tarifa: estimativa zero, mas 30 e 10 mensagens → fatura de R$ 8 rateada 6 e 2
        InvoiceReconciler.reconcile(OCTOBER, ChannelItem.SMS, new BigDecimal("8.00"),
                estimate(key(5, C1, P1), "0", "30", key(6, C2, null), "0", "10"), acc);
        assertThat(fact(acc.facts(), key(5, C1, P1)).amount()).isEqualByComparingTo("6");
        assertThat(fact(acc.facts(), key(6, C2, null)).amount()).isEqualByComparingTo("2");
        assertThat(acc.facts()).allSatisfy(f -> assertThat(f.category()).isEqualTo(CostCategory.MESSAGING));
        assertThat(acc.warnings()).isEmpty();
    }

    @Test
    void semUsoNoMesOValorFicaSemCredorNoPrimeiroDiaComAviso() {
        CostAccumulator acc = new CostAccumulator();
        // a linha de setembro não conta (fora do mês) e a de outubro não tem uso
        Map<CostKey, ItemEstimate> estimate = estimate(key(9, C1, P1), "0", "0");
        estimate.put(new CostKey(LocalDate.of(2026, 9, 30), C1, P1), new ItemEstimate(BigDecimal.TEN, BigDecimal.TEN));
        BigDecimal adjustment = InvoiceReconciler.reconcile(OCTOBER, ChannelItem.AI, new BigDecimal("42.50"), estimate,
                acc);
        assertThat(adjustment).isEqualByComparingTo("42.50");
        assertThat(acc.facts()).singleElement().satisfies(f -> {
            assertThat(f.day()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(f.creditorId()).isNull();
            assertThat(f.portfolioId()).isNull();
            assertThat(f.category()).isEqualTo(CostCategory.AI);
            assertThat(f.amount()).isEqualByComparingTo("42.50");
        });
        assertThat(acc.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(CostWarningCode.INVOICE_WITHOUT_USAGE);
            assertThat(w.creditorId()).isNull();
            assertThat(w.quantity()).isEqualByComparingTo("1");
        });

        // fatura zero sem uso: nada a ajustar e nenhum aviso
        CostAccumulator none = new CostAccumulator();
        InvoiceReconciler.reconcile(OCTOBER, ChannelItem.EMAIL, BigDecimal.ZERO, Map.of(), none);
        assertThat(none.facts()).isEmpty();
        assertThat(none.warnings()).isEmpty();
    }

    @Test
    void faturaIgualAEstimativaNaoGeraAjuste() {
        CostAccumulator acc = new CostAccumulator();
        InvoiceReconciler.reconcile(OCTOBER, ChannelItem.TELEPHONY, new BigDecimal("50.00"),
                estimate(key(1, C1, P1), "50", "500"), acc);
        assertThat(acc.facts()).isEmpty();
    }

    @Test
    void itemDaTarifaESituacaoDaConciliacao() {
        assertThat(ChannelItem.of(TariffChannel.WHATSAPP_MARKETING)).isEqualTo(ChannelItem.WHATSAPP);
        assertThat(ChannelItem.of(TariffChannel.EMAIL)).isEqualTo(ChannelItem.EMAIL);
        assertThat(ChannelItem.AI.category()).isEqualTo(CostCategory.AI);
        Set<ChannelItem> used = EnumSet.of(ChannelItem.TELEPHONY, ChannelItem.WHATSAPP);
        assertThat(ReconciliationStatus.of(used, Set.of())).isEqualTo(ReconciliationStatus.ESTIMATED);
        assertThat(ReconciliationStatus.of(used, Set.of(ChannelItem.TELEPHONY))).isEqualTo(ReconciliationStatus.PARTIAL);
        assertThat(ReconciliationStatus.of(used, EnumSet.of(ChannelItem.TELEPHONY, ChannelItem.WHATSAPP, ChannelItem.AI)))
                .isEqualTo(ReconciliationStatus.RECONCILED);
        assertThat(ReconciliationStatus.of(Set.of(), Set.of(ChannelItem.SMS))).isEqualTo(ReconciliationStatus.RECONCILED);
        assertThat(ReconciliationStatus.combine(List.of(ReconciliationStatus.RECONCILED, ReconciliationStatus.ESTIMATED)))
                .isEqualTo(ReconciliationStatus.PARTIAL);
        assertThat(ReconciliationStatus.combine(List.of(ReconciliationStatus.RECONCILED)))
                .isEqualTo(ReconciliationStatus.RECONCILED);
        assertThat(ReconciliationStatus.combine(List.of())).isEqualTo(ReconciliationStatus.ESTIMATED);
    }

    @Test
    void faturaValidaValorCasasEFornecedor() {
        ChannelInvoice invoice = new ChannelInvoice(OCTOBER, ChannelItem.SMS, new BigDecimal("12.5"), "  Operadora  ",
                " ");
        assertThat(invoice.getAmount()).isEqualByComparingTo("12.50");
        assertThat(invoice.getAmount().scale()).isEqualTo(2);
        assertThat(invoice.getSupplier()).isEqualTo("Operadora");
        assertThat(invoice.getNote()).isNull();
        assertThat(invoice.getMonth()).isEqualTo(OCTOBER);
        invoice.replace(BigDecimal.ZERO, null, "franquia");
        assertThat(invoice.getAmount()).isZero();
        assertThatThrownBy(() -> invoice.replace(new BigDecimal("-1"), null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> invoice.replace(new BigDecimal("1.001"), null, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> invoice.replace(BigDecimal.ONE, "x".repeat(121), null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ChannelInvoice(OCTOBER, null, BigDecimal.ONE, null, null))
                .isInstanceOf(DomainException.class);
    }
}
