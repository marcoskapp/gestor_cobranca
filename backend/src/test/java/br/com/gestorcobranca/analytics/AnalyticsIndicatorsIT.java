package br.com.gestorcobranca.analytics;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ConversionReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ConversionRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.PortfolioReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ProductivityReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ProductivityRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.RecoveryReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.RecoveryRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ReliabilityReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cada indicador com dados conhecidos e o resultado conferido à mão (contas nos comentários e em
 * docs/negocio/indicadores.md). Período de referência: junho/2026.
 */
class AnalyticsIndicatorsIT extends AnalyticsTestSupport {

    static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
    static final LocalDate JUNE_30 = LocalDate.of(2026, 6, 30);

    @Autowired
    AnalyticsQueries queries;
    @Autowired
    AnalyticsRefreshUseCases refresh;

    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;
    UUID queue;
    UUID case1;
    UUID case2;

    @BeforeEach
    void scenario() {
        provisionTenant("ind");
        run(() -> {
            Facts f = new Facts();
            creditorA = f.creditor("Banco Alfa", "A");
            creditorB = f.creditor("Loja Beta", "B");
            portfolioA = f.portfolio(creditorA, "PA");
            portfolioB = f.portfolio(creditorB, "PB");
            queue = f.queue("Q1");
            UUID d1 = f.debtor();
            UUID d2 = f.debtor();
            UUID d3 = f.debtor();
            UUID d4 = f.debtor();
            LocalDate today = today();
            // carteira: 1.000 (50 dias de atraso) + 500 (a vencer) + 2.000 (> 360 dias) + 300 suspenso (4 dias);
            // título quitado fica fora
            f.title(creditorA, portfolioA, d1, today.minusDays(50), "OPEN", "1000.00");
            f.title(creditorA, portfolioA, d1, today.plusDays(60), "OPEN", "500.00");
            f.title(creditorB, portfolioB, d3, today.minusDays(400), "OPEN", "2000.00");
            f.title(creditorA, portfolioA, d2, today.minusDays(10), "SETTLED", "0");
            f.title(creditorB, portfolioB, d4, today.minusDays(4), "SUSPENDED", "300.00");
            case1 = f.collectionCase(d1, creditorA, portfolioA, "OPEN", true, "1500.00", "1500.00", "QUEUE", queue, null,
                    today.minusDays(50));
            case2 = f.collectionCase(d3, creditorB, portfolioB, "IN_NEGOTIATION", true, "2000.00", "2000.00", "USER",
                    null, operatorX, today.minusDays(400));
            UUID case3 = f.collectionCase(d4, creditorB, portfolioB, "SUSPENDED", false, "300.00", "0.00", "NONE", null,
                    null, today.minusDays(4));
            f.collectionCase(d2, creditorA, portfolioA, "CLOSED", false, "0.00", "0.00", "NONE", null, null, null);

            // denominador: último retrato antes de 01/06 00:00 — caso 1 = 2.500 (opX), caso 2 = 1.500 (opY)
            f.caseSnapshot(case1, creditorA, portfolioA, operatorX, at(2026, 5, 10, 9), "3000.00");
            f.caseSnapshot(case1, creditorA, portfolioA, operatorX, at(2026, 5, 30, 9), "2500.00");
            f.caseSnapshot(case1, creditorA, portfolioA, operatorX, at(2026, 6, 15, 9), "100.00");
            f.caseSnapshot(case2, creditorB, portfolioB, operatorY, at(2026, 5, 15, 9), "1500.00");
            f.caseSnapshot(case3, creditorB, portfolioB, null, at(2026, 5, 1, 9), "0.00");

            // acordos
            UUID ag0 = f.agreement(creditorA, d1, portfolioA, case1, "HUMAN", "USER", operatorX, "PHONE",
                    at(2026, 5, 20, 10), "400.00", 1, "SETTLED", at(2026, 5, 25, 10), null);
            UUID ag1 = f.agreement(creditorA, d1, portfolioA, case1, "HUMAN", "USER", operatorX, "PHONE",
                    at(2026, 6, 5, 10), "1200.00", 3, "IN_PAYMENT", null, null);
            UUID ag2 = f.agreement(creditorB, d3, portfolioB, case2, "ASSISTANT_AUTONOMOUS", "ASSISTANT", ASSISTANT_ID,
                    "WHATSAPP", at(2026, 6, 12, 10), "800.00", 2, "BROKEN", at(2026, 6, 25, 10), null);
            UUID ag4 = f.agreement(creditorB, d3, portfolioB, case2, "HUMAN", "USER", operatorY, "PHONE",
                    at(2026, 6, 26, 10), "700.00", 1, "IN_PAYMENT", null, ag2);
            f.installment(ag0, 1, LocalDate.of(2026, 5, 25), "400.00", "PAID", at(2026, 5, 25, 11));
            f.installment(ag1, 1, LocalDate.of(2026, 6, 10), "400.00", "PAID", at(2026, 6, 10, 11));
            f.installment(ag1, 2, LocalDate.of(2026, 6, 28), "400.00", "PAID", at(2026, 6, 28, 11));
            f.installment(ag1, 3, LocalDate.of(2026, 7, 28), "400.00", "OPEN", null);
            f.installment(ag2, 1, LocalDate.of(2026, 6, 15), "400.00", "OVERDUE", null);
            f.installment(ag4, 1, LocalDate.of(2026, 7, 2), "700.00", "OPEN", null);
            // proposta recusada (sem acordo) em junho
            f.proposal(creditorA, d1, portfolioA, case1, "USER", operatorX, "PHONE", "REJECTED", at(2026, 6, 7, 10));

            // recebimentos (líquidos de estorno, na data do fato)
            f.receipt(creditorA, portfolioA, ag0, "400.00", LocalDate.of(2026, 5, 25), 40);
            f.receipt(creditorA, portfolioA, ag1, "400.00", LocalDate.of(2026, 6, 10), 45);
            f.receipt(creditorA, portfolioA, ag1, "400.00", LocalDate.of(2026, 6, 28), 45);
            UUID r3 = f.receipt(creditorB, portfolioB, ag2, "200.00", LocalDate.of(2026, 6, 15), 400);
            f.reversal(r3, "200.00", LocalDate.of(2026, 6, 20));
            f.receipt(creditorA, portfolioA, null, "300.00", LocalDate.of(2026, 6, 3), 10);
            f.receipt(creditorB, portfolioB, ag4, "100.00", LocalDate.of(2026, 7, 2), 400);

            // contatos efetivos de junho: opX (1), opY (1), assistente (1); não efetivo e de maio ficam fora
            f.interaction(case1, d1, creditorA, portfolioA, "PHONE", true, operatorX, "USER", at(2026, 6, 3, 10));
            f.interaction(case1, d1, creditorA, portfolioA, "PHONE", false, operatorX, "USER", at(2026, 6, 4, 10));
            f.interaction(case2, d3, creditorB, portfolioB, "PHONE", true, operatorY, "USER", at(2026, 6, 8, 10));
            f.interaction(case2, d3, creditorB, portfolioB, "WHATSAPP", true, null, "ASSISTANT", at(2026, 6, 11, 10));
            f.interaction(case1, d1, creditorA, portfolioA, "PHONE", true, operatorX, "USER", at(2026, 5, 30, 10));

            // fatos do incentive
            f.performance(operatorX, LocalDate.of(2026, 6, 3), creditorA, portfolioA, "PHONE", 5, 2, 3, 600);
            f.performance(operatorX, LocalDate.of(2026, 6, 4), creditorB, portfolioB, "PHONE", 1, 0, 0, 0);
            f.performance(operatorY, LocalDate.of(2026, 6, 8), creditorB, portfolioB, "PHONE", 4, 1, 2, 300);
            f.performance(operatorX, LocalDate.of(2026, 5, 29), creditorA, portfolioA, "PHONE", 10, 5, 5, 999);
            f.workedCase(operatorX, LocalDate.of(2026, 6, 3), case1, creditorA, portfolioA);
            f.workedCase(operatorX, LocalDate.of(2026, 6, 4), case1, creditorA, portfolioA);
            f.workedCase(operatorX, LocalDate.of(2026, 6, 4), case2, creditorB, portfolioB);
            f.workedCase(operatorY, LocalDate.of(2026, 6, 8), case2, creditorB, portfolioB);
            f.loggedTime(operatorX, LocalDate.of(2026, 6, 3), 7200);
            f.loggedTime(operatorX, LocalDate.of(2026, 6, 4), 3600);
            f.loggedTime(operatorY, LocalDate.of(2026, 6, 8), 5400);

            // confiabilidade
            f.instruction(creditorA, d1, "FAILED", "PROVIDER_TIMEOUT", at(2026, 6, 2, 10));
            f.instruction(creditorA, d1, "ACTIVE", null, at(2026, 6, 2, 11));
            f.divergence(creditorA, "AMOUNT_MISMATCH", "OPEN", at(2026, 6, 3, 10), null);
            f.divergence(creditorA, "DUPLICATE_PAYMENT", "RESOLVED", at(2026, 6, 2, 10), at(2026, 6, 2, 16));
            f.importBatch(creditorA, portfolioA, "COMPLETED", at(2026, 6, 5, 10), 30, 100, 5);
            f.importBatch(creditorA, portfolioA, "FAILED", at(2026, 6, 6, 10), 90, 0, 0);
            f.deadJob(tenant, "payment.pix_sync");
            f.deadJob(UUID.randomUUID(), "outra.assessoria");
        });
        as(manager, DefaultRoles.GESTOR, () -> refresh.refreshNow());
    }

    private static ReportFilter june() {
        return new ReportFilter(JUNE_1, JUNE_30, null, null, null, null, null);
    }

    @Test
    void portfolioPosition() {
        PortfolioReport r = as(manager, DefaultRoles.GESTOR, () -> queries.portfolio(june()));
        var t = r.totals();
        // 1.000 + 500 + 2.000 + 300
        assertThat(t.portfolioAmount()).isEqualByComparingTo("3800.00");
        assertThat(t.openTitles()).isEqualTo(4);
        // vencidos: 1.000 + 2.000 + 300 = 3.300 ÷ 3.800 = 0,86842 → 0,8684
        assertThat(t.overdueAmount()).isEqualByComparingTo("3300.00");
        assertThat(t.overdueShare().value()).isEqualByComparingTo("0.8684");
        assertThat(t.overdueShare().denominator()).isEqualByComparingTo("3800.00");
        // casos não encerrados: 1.500 + 2.000 + 300; elegível 1.500 + 2.000 = 3.500 ÷ 3.800 = 0,9211
        assertThat(t.caseOpenBalance()).isEqualByComparingTo("3800.00");
        assertThat(t.eligibleBalance()).isEqualByComparingTo("3500.00");
        assertThat(t.eligibleShare().value()).isEqualByComparingTo("0.9211");
        assertThat(t.openCases()).isEqualTo(3);
        assertThat(t.eligibleCases()).isEqualTo(2);
        // fila pendente: caso 1 (elegível, na fila) ÷ 2 elegíveis
        assertThat(t.pendingQueueCases()).isEqualTo(1);
        assertThat(t.pendingQueueBalance()).isEqualByComparingTo("1500.00");
        assertThat(t.pendingQueueShare().value()).isEqualByComparingTo("0.5000");
        assertThat(r.pendingQueue()).singleElement().satisfies(q -> assertThat(q.label()).isEqualTo("Fila Q1"));

        Map<String, AnalyticsQueries.PortfolioRow> byCreditor = r.byCreditor().stream()
                .collect(Collectors.toMap(AnalyticsQueries.PortfolioRow::label, Function.identity()));
        assertThat(byCreditor.get("Banco Alfa").portfolioAmount()).isEqualByComparingTo("1500.00");
        assertThat(byCreditor.get("Banco Alfa").overdueAmount()).isEqualByComparingTo("1000.00");
        assertThat(byCreditor.get("Loja Beta").portfolioAmount()).isEqualByComparingTo("2300.00");
        assertThat(byCreditor.get("Loja Beta").eligibleBalance()).isEqualByComparingTo("2000.00");
        Map<String, BigDecimal> byBucket = r.byDelayBucket().stream().collect(Collectors.toMap(
                AnalyticsQueries.PortfolioRow::key, AnalyticsQueries.PortfolioRow::portfolioAmount));
        assertThat(byBucket).containsOnlyKeys("NOT_DUE", "D0_30", "D31_90", "D361_PLUS");
        assertThat(byBucket.get("D31_90")).isEqualByComparingTo("1000.00");
        assertThat(r.byDelayBucket()).extracting(AnalyticsQueries.PortfolioRow::key)
                .containsExactly("NOT_DUE", "D0_30", "D31_90", "D361_PLUS");
        assertThat(r.casesByStatus()).extracting(AnalyticsQueries.CaseStatusRow::status)
                .containsExactly("OPEN", "IN_NEGOTIATION", "SUSPENDED");
        assertThat(r.meta().dataUntil()).isNotNull();
        assertThat(r.meta().notApplicableFilters()).contains("from", "to");

        // escopo de credores: só a Loja Beta
        PortfolioReport scoped = asScoped(manager, DefaultRoles.GESTOR, Set.of(creditorB), () -> queries.portfolio(june()));
        assertThat(scoped.totals().portfolioAmount()).isEqualByComparingTo("2300.00");
        // pedir um credor fora do escopo não amplia nada
        PortfolioReport outside = asScoped(manager, DefaultRoles.GESTOR, Set.of(creditorB), () -> queries.portfolio(
                new ReportFilter(JUNE_1, JUNE_30, Set.of(creditorA), null, null, null, null)));
        assertThat(outside.totals().portfolioAmount()).isEqualByComparingTo("0");
    }

    @Test
    void recoveryNetOfReversalsWithExplicitDenominator() {
        RecoveryReport r = as(manager, DefaultRoles.GESTOR, () -> queries.recovery(june(), "ORIGIN"));
        var t = r.totals();
        // junho: 400 + 400 + 200 + 300 recebidos; estorno de 200 → líquido 1.100 (maio e julho fora)
        assertThat(t.recoveredGross()).isEqualByComparingTo("1300.00");
        assertThat(t.reversed()).isEqualByComparingTo("200.00");
        assertThat(t.recoveredNet()).isEqualByComparingTo("1100.00");
        assertThat(t.receipts()).isEqualTo(4);
        // 1.100 ÷ (2.500 + 1.500) = 0,2750
        assertThat(t.recoveryRate().value()).isEqualByComparingTo("0.2750");
        assertThat(t.recoveryRate().denominator()).isEqualByComparingTo("4000.00");
        // 200 ÷ 1.300 = 0,15385 → 0,1538
        assertThat(t.reversalShare().value()).isEqualByComparingTo("0.1538");
        Map<String, RecoveryRow> byOrigin = r.rows().stream().collect(Collectors.toMap(RecoveryRow::key, x -> x));
        assertThat(byOrigin.get("HUMAN").recoveredNet()).isEqualByComparingTo("800.00");
        assertThat(byOrigin.get("ASSISTANT").recoveredGross()).isEqualByComparingTo("200.00");
        assertThat(byOrigin.get("ASSISTANT").recoveredNet()).isEqualByComparingTo("0.00");
        assertThat(byOrigin.get("NO_AGREEMENT").recoveredNet()).isEqualByComparingTo("300.00");
        assertThat(byOrigin.get("HUMAN").recoveryRate().unavailableReason()).isEqualTo("not_segmentable");

        // tempo até recebimento: ag1 = 5 dias, ag2 = 3 dias → média 4, mediana 4, p90 = 3 + 0,9 × 2 = 4,8
        assertThat(r.timeToReceipt().agreements()).isEqualTo(2);
        assertThat(r.timeToReceipt().averageDays()).isEqualByComparingTo("4.00");
        assertThat(r.timeToReceipt().medianDays()).isEqualByComparingTo("4.00");
        assertThat(r.timeToReceipt().p90Days()).isEqualByComparingTo("4.80");
        // sem extratos de comissão aprovados no mês: sem custo
        assertThat(r.cost().commissionPerReal().unavailableReason()).isEqualTo("no_source");
        assertThat(r.meta().unavailableMetrics()).contains("cost.channelCosts");

        RecoveryReport byCreditor = as(manager, DefaultRoles.GESTOR, () -> queries.recovery(june(), "CREDITOR"));
        Map<String, RecoveryRow> c = byCreditor.rows().stream().collect(Collectors.toMap(RecoveryRow::label, x -> x));
        // Alfa: 1.100 ÷ 2.500 = 0,44; Beta: 0 ÷ 1.500 = 0
        assertThat(c.get("Banco Alfa").recoveryRate().value()).isEqualByComparingTo("0.4400");
        assertThat(c.get("Loja Beta").recoveryRate().value()).isEqualByComparingTo("0.0000");

        RecoveryReport byOperator = as(manager, DefaultRoles.GESTOR, () -> queries.recovery(june(), "OPERATOR"));
        Map<String, RecoveryRow> o = byOperator.rows().stream().collect(Collectors.toMap(RecoveryRow::key, x -> x));
        // opX: 800 ÷ 2.500 (caso 1 atribuído) = 0,32; sem negociador humano: 300 (título avulso) + 0 (assistente)
        assertThat(o.get(operatorX.toString()).recoveryRate().value()).isEqualByComparingTo("0.3200");
        assertThat(o.get(operatorX.toString()).label()).isEqualTo("Operador X");
        assertThat(o.get("-").recoveredNet()).isEqualByComparingTo("300.00");
        assertThat(o.get("-").label()).isEqualTo("Sem negociador humano");

        RecoveryReport monthly = as(manager, DefaultRoles.GESTOR, () -> queries.recovery(
                new ReportFilter(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 31), null, null, null, null, null),
                "MONTH"));
        assertThat(monthly.rows()).extracting(RecoveryRow::key).containsExactly("2026-05", "2026-06", "2026-07");
        assertThat(monthly.rows()).extracting(RecoveryRow::recoveredNet).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("400"), new BigDecimal("1100"), new BigDecimal("100"));
        // filtro de canal: o retrato de saldo não tem canal → taxa não segmentável
        RecoveryReport phone = as(manager, DefaultRoles.GESTOR, () -> queries.recovery(
                new ReportFilter(JUNE_1, JUNE_30, null, null, null, null, Set.of("PHONE")), null));
        assertThat(phone.totals().recoveredNet()).isEqualByComparingTo("800.00");
        assertThat(phone.totals().recoveryRate().unavailableReason()).isEqualTo("not_segmentable");
    }

    @Test
    void conversionFunnelSeparatesAgreedFromPaid() {
        ConversionReport r = as(manager, DefaultRoles.GESTOR, () -> queries.conversion(june(), "ORIGIN"));
        var f = r.funnel();
        // contatos efetivos 3 (opX, opY, assistente); propostas 4 (3 formalizadas + 1 recusada); acordos 3; 1ª paga 1
        assertThat(f.effectiveContacts()).isEqualTo(3);
        assertThat(f.proposals()).isEqualTo(4);
        assertThat(f.agreements()).isEqualTo(3);
        assertThat(f.firstInstallmentsPaid()).isEqualTo(1);
        assertThat(f.contactToProposal().value()).isEqualByComparingTo("1.3333");
        assertThat(f.proposalToAgreement().value()).isEqualByComparingTo("0.7500");
        assertThat(f.agreementToFirstInstallment().value()).isEqualByComparingTo("0.3333");
        assertThat(f.contactToAgreement().value()).isEqualByComparingTo("1.0000");
        var q = r.quality();
        // acordado 1.200 + 800 + 700 = 2.700; pago 800 (ag1) + 0 (ag2, estornado) + 100 (ag4, em julho) = 900
        assertThat(q.agreedAmount()).isEqualByComparingTo("2700.00");
        assertThat(q.paidAmount()).isEqualByComparingTo("900.00");
        assertThat(q.paidShare().value()).isEqualByComparingTo("0.3333");
        // parcelas pagas em junho: ag1 nº 1 e nº 2
        assertThat(q.installmentsPaid()).isEqualTo(2);
        assertThat(q.installmentsPaidAmount()).isEqualByComparingTo("800.00");
        assertThat(q.renegotiations()).isEqualTo(1);
        assertThat(q.renegotiationRate().value()).isEqualByComparingTo("0.3333");
        // safra madura nas três janelas; ag2 quebrou em 13 dias
        assertThat(q.breaks()).allSatisfy(b -> {
            assertThat(b.matured()).isEqualTo(3);
            assertThat(b.broken()).isEqualTo(1);
            assertThat(b.rate().value()).isEqualByComparingTo("0.3333");
        });
        Map<String, ConversionRow> byOrigin = r.rows().stream().collect(Collectors.toMap(ConversionRow::key, x -> x));
        assertThat(byOrigin.get("HUMAN").agreements()).isEqualTo(2);
        assertThat(byOrigin.get("HUMAN").agreedAmount()).isEqualByComparingTo("1900.00");
        assertThat(byOrigin.get("HUMAN").paidAmount()).isEqualByComparingTo("900.00");
        assertThat(byOrigin.get("ASSISTANT").paidAmount()).isEqualByComparingTo("0.00");
        // contato e proposta não têm origem de acordo
        assertThat(byOrigin.get("HUMAN").effectiveContacts()).isNull();

        ConversionReport byOperator = as(manager, DefaultRoles.GESTOR, () -> queries.conversion(june(), "OPERATOR"));
        ConversionRow x = byOperator.rows().stream().filter(row -> row.key().equals(operatorX.toString())).findFirst()
                .orElseThrow();
        // opX: 1 contato efetivo, 2 propostas (ag1 + recusada), 1 acordo
        assertThat(x.effectiveContacts()).isEqualTo(1);
        assertThat(x.proposals()).isEqualTo(2);
        assertThat(x.agreements()).isEqualTo(1);
    }

    @Test
    void productivityReusesIncentiveFactsAndFollowsPeopleScope() {
        ProductivityReport r = as(manager, DefaultRoles.GESTOR, () -> queries.productivity(june(), "OPERATOR"));
        Map<String, ProductivityRow> rows = r.rows().stream().collect(Collectors.toMap(ProductivityRow::key, x -> x));
        ProductivityRow x = rows.get(operatorX.toString());
        assertThat(x.workedCases()).isEqualTo(2);
        assertThat(x.attempts()).isEqualTo(6);
        assertThat(x.effectiveContacts()).isEqualTo(2);
        assertThat(x.contactRate().value()).isEqualByComparingTo("0.3333");
        // 600 s ÷ 3 atendidas
        assertThat(x.averageHandlingSeconds().value()).isEqualByComparingTo("200.0000");
        assertThat(x.loggedHours()).isEqualByComparingTo("3.00");
        assertThat(x.agreements()).isEqualTo(1);
        assertThat(x.agreementsPerLoggedHour().value()).isEqualByComparingTo("0.3333");
        assertThat(x.recoveredNet()).isEqualByComparingTo("800.00");
        ProductivityRow y = rows.get(operatorY.toString());
        assertThat(y.averageHandlingSeconds().value()).isEqualByComparingTo("150.0000");
        assertThat(y.agreementsPerLoggedHour().value()).isEqualByComparingTo("0.6667");
        assertThat(y.recoveredNet()).isEqualByComparingTo("0");
        var total = r.total();
        // 10 tentativas, 3 contatos; 900 s ÷ 5; 2 acordos ÷ 4,5 h
        assertThat(total.attempts()).isEqualTo(10);
        assertThat(total.contactRate().value()).isEqualByComparingTo("0.3000");
        assertThat(total.averageHandlingSeconds().value()).isEqualByComparingTo("180.0000");
        assertThat(total.loggedHours()).isEqualByComparingTo("4.50");
        assertThat(total.agreementsPerLoggedHour().value()).isEqualByComparingTo("0.4444");

        ProductivityReport byTeam = as(manager, DefaultRoles.GESTOR, () -> queries.productivity(june(), "TEAM"));
        assertThat(byTeam.rows()).singleElement().satisfies(t -> {
            assertThat(t.label()).isEqualTo("Equipe Varejo");
            assertThat(t.attempts()).isEqualTo(10);
            assertThat(t.workedCases()).isEqualTo(3);
        });

        // operador vê só o próprio desempenho
        ProductivityReport own = as(operatorX, DefaultRoles.OPERADOR, () -> queries.productivity(june(), "OPERATOR"));
        assertThat(own.rows()).extracting(ProductivityRow::key).containsExactly(operatorX.toString());
        assertThat(own.total().attempts()).isEqualTo(6);
        assertThat(own.meta().peopleRestricted()).isTrue();
        assertThatThrownBy(() -> as(operatorY, DefaultRoles.OPERADOR, () -> queries.productivity(
                new ReportFilter(JUNE_1, JUNE_30, null, null, Set.of(operatorX), null, null), "OPERATOR")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(operatorY, DefaultRoles.OPERADOR, () -> queries.productivity(
                new ReportFilter(JUNE_1, JUNE_30, null, null, null, Set.of(team), null), "OPERATOR")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // supervisor vê a equipe
        ProductivityReport sup = as(supervisor, DefaultRoles.SUPERVISOR, () -> queries.productivity(june(), "OPERATOR"));
        assertThat(sup.rows()).extracting(ProductivityRow::key)
                .containsExactlyInAnyOrder(operatorX.toString(), operatorY.toString());
        // recuperação por pessoa também segue o escopo; por credor não
        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> queries.recovery(june(), "OPERATOR")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        RecoveryReport supRecovery = as(supervisor, DefaultRoles.SUPERVISOR, () -> queries.recovery(june(), "OPERATOR"));
        assertThat(supRecovery.meta().peopleRestricted()).isTrue();
        assertThat(supRecovery.rows()).extracting(RecoveryRow::key).doesNotContain("-");
        assertThat(supRecovery.totals().recoveredNet()).isEqualByComparingTo("800.00");

        // recorte por credor: tempo logado não é segmentável
        ProductivityReport alfa = as(manager, DefaultRoles.GESTOR, () -> queries.productivity(
                new ReportFilter(JUNE_1, JUNE_30, Set.of(creditorA), null, null, null, null), "OPERATOR"));
        ProductivityRow xa = alfa.rows().stream().filter(row -> row.key().equals(operatorX.toString())).findFirst()
                .orElseThrow();
        assertThat(xa.attempts()).isEqualTo(5);
        assertThat(xa.workedCases()).isEqualTo(1);
        assertThat(xa.agreementsPerLoggedHour().unavailableReason()).isEqualTo("not_segmentable");
        assertThat(alfa.meta().notApplicableFilters()).isNotEmpty();
    }

    @Test
    void reliabilityCountsFailuresWithDenominators() {
        ReliabilityReport r = as(manager, DefaultRoles.GESTOR, () -> queries.reliability(june()));
        assertThat(r.integrations().paymentInstructions()).isEqualTo(2);
        assertThat(r.integrations().instructionFailureRate().value()).isEqualByComparingTo("0.5000");
        assertThat(r.integrations().instructionFailuresByCode()).singleElement()
                .satisfies(c -> assertThat(c.key()).isEqualTo("PROVIDER_TIMEOUT"));
        assertThat(r.reconciliation().opened()).isEqualTo(2);
        assertThat(r.reconciliation().openNow()).isEqualTo(1);
        assertThat(r.reconciliation().resolved()).isEqualTo(1);
        assertThat(r.reconciliation().medianResolutionHours()).isEqualByComparingTo("6.00");
        assertThat(r.messages().duplicatePaymentDivergences()).isEqualTo(1);
        // DEAD só da própria assessoria (a fila de jobs é global; a view filtra)
        assertThat(r.jobs().deadNow()).isEqualTo(1);
        assertThat(r.jobs().deadByType()).extracting(AnalyticsQueries.CountRow::key).containsExactly("payment.pix_sync");
        // importações: 30 s e 90 s → mediana 60, p95 = 30 + 0,95 × 60 = 87; 1 falha de 2; 5 linhas com erro de 100
        assertThat(r.imports().batches()).isEqualTo(2);
        assertThat(r.imports().failureRate().value()).isEqualByComparingTo("0.5000");
        assertThat(r.imports().medianSeconds()).isEqualByComparingTo("60.00");
        assertThat(r.imports().p95Seconds()).isEqualByComparingTo("87.00");
        assertThat(r.imports().rowErrorRate().value()).isEqualByComparingTo("0.0500");
        assertThat(r.meta().unavailableMetrics()).contains("messages.duplicates");
        // escopo restrito: jobs (sem credor) não aparecem
        ReliabilityReport scoped = asScoped(manager, DefaultRoles.GESTOR, Set.of(creditorA),
                () -> queries.reliability(june()));
        assertThat(scoped.jobs()).isNull();
        assertThat(scoped.integrations().paymentInstructions()).isEqualTo(2);
    }

    @Test
    void permissionsAreChecked() {
        // operador não tem REPORT_VIEW nem DASHBOARD_VIEW
        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> queries.recovery(june(), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> queries.portfolio(june())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'ACCESS_DENIED' and actor_id = ?",
                operatorX)).isGreaterThanOrEqualTo(2);
        assertThat(as(manager, DefaultRoles.GESTOR, () -> queries.freshness()))
                .extracting(AnalyticsQueries.DatasetFreshness::dataset)
                .containsExactlyInAnyOrder("recovery_daily", "agreement_cohort", "portfolio_position", "case_position");
        assertThat(List.of(1)).isNotEmpty();
    }
}
