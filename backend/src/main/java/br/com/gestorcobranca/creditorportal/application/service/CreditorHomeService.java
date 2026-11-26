package br.com.gestorcobranca.creditorportal.application.service;

import br.com.gestorcobranca.analytics.application.port.in.creditor.CreditorIndicatorsQueries;
import br.com.gestorcobranca.analytics.application.port.in.creditor.CreditorIndicatorsQueries.CreditorIndicators;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases.CreditorDisputesAwaiting;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorHomeUseCases;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases.ReportedPayment;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorOpenPeriod;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorStatementSummary;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.TemporarilyUnavailableException;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Início do portal do credor (fatia 7): junta os indicadores do analytics, o repasse estimado do settlement e o
 * "Aguardando você" de collection, settlement e payment, sempre pelas portas {@code :: creditor} dos donos (cada uma
 * confere o ator e a permissão). <b>Sem transação própria</b>: cada fonte roda na transação da porta dela, para que a
 * falha técnica de uma (banco, tempo limite de {@code app.creditor-portal.source-timeout}) deixe só aquela seção
 * indisponível — numa transação comum, a primeira falha marcaria a transação inteira para desfazer. Recusa de
 * permissão, escopo, regra e erro de programação continuam subindo.
 */
@Service
class CreditorHomeService implements CreditorHomeUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(CreditorHomeService.class);
    static final int AWAITING_ITEMS = 50;

    private final CreditorIndicatorsQueries indicators;
    private final CreditorSettlementUseCases settlement;
    private final CreditorDisputeUseCases disputes;
    private final CreditorPaymentUseCases payments;
    private final CurrentActor currentActor;
    private final CreditorPortalMetrics metrics;
    private final Clock clock;

    CreditorHomeService(CreditorIndicatorsQueries indicators, CreditorSettlementUseCases settlement,
                        CreditorDisputeUseCases disputes, CreditorPaymentUseCases payments, CurrentActor currentActor,
                        CreditorPortalMetrics metrics, Clock clock) {
        this.indicators = indicators;
        this.settlement = settlement;
        this.disputes = disputes;
        this.payments = payments;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Overview overview(String period) {
        long start = System.nanoTime();
        Instant generatedAt = Instant.now(clock);
        List<String> unavailable = new ArrayList<>();
        CreditorIndicators ind = source("INDICATORS", unavailable, () -> indicators.indicators(period));
        CreditorOpenPeriod open = source("TRANSFER", unavailable, settlement::openPeriod);
        Overview overview = new Overview(
                ind == null ? period : ind.period(), ind == null ? null : ind.from(), ind == null ? null : ind.to(),
                ind == null ? null : ind.previousFrom(), ind == null ? null : ind.previousTo(), generatedAt,
                ind == null ? null : ind.dataUntil(),
                ind == null ? null : new Recovery(ind.recovery().recovered(), ind.recovery().paidToCreditor(),
                        ind.recovery().previousRecovered(), ind.recovery().previousPaidToCreditor()),
                ind == null ? null : ind.monthly().stream()
                        .map(m -> new Month(m.month(), m.recovered(), m.paidToCreditor())).toList(),
                ind == null ? null : ind.byPortfolio().stream()
                        .map(p -> new PortfolioShare(p.portfolioId(), p.name(), p.recovered(), p.others())).toList(),
                ind == null ? null : new Balance(ind.balance().amount(), ind.balance().titles(),
                        ind.balance().referenceDate()),
                ind == null ? null : new Agreements(ind.agreements().onTrack(), ind.agreements().active(),
                        ind.agreements().ratio(), ind.agreements().alert(), ind.agreements().referenceDate()),
                open == null ? null : new Transfer(open.periodStart(), open.asOf(), open.expectedPeriodEnd(),
                        open.netTransfer(), open.direction(), open.calculatedAt()),
                List.copyOf(unavailable));
        metrics.overview(Duration.ofNanos(System.nanoTime() - start), ind == null ? "none" : ind.cached() ? "hit" : "miss");
        return overview;
    }

    @Override
    public Awaiting awaiting() {
        List<String> unavailable = new ArrayList<>();
        CreditorDisputesAwaiting d = source("DISPUTES", unavailable, () -> disputes.awaiting(AWAITING_ITEMS));
        List<CreditorStatementSummary> s = source("STATEMENTS", unavailable, settlement::awaitingResponse);
        List<ReportedPayment> p = source("PAYMENTS", unavailable, payments::recentlyRejected);
        br.com.gestorcobranca.shared.security.Actor actor = currentActor.get();
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate soon = today.plusDays(CreditorDisputeUseCases.DUE_SOON_DAYS);

        List<AwaitingItem> items = new ArrayList<>();
        int total = 0;
        int dueSoon = 0;
        int overdue = 0;
        if (d != null) {
            boolean can = actor.has(Permission.CREDITOR_DISPUTE_RESPOND);
            d.items().forEach(i -> items.add(new AwaitingItem("DISPUTE", i.id(), i.requestedDueDate(), i.overdue(),
                    i.dueSoon(), can, new AwaitingDispute(i.protocol(), i.type()), null, null)));
            total += d.total();
            dueSoon += d.dueSoon();
            overdue += d.overdue();
        }
        if (s != null) {
            boolean can = actor.has(Permission.CREDITOR_STATEMENT_RESPOND);
            for (CreditorStatementSummary st : s) {
                boolean soonDue = !st.responseOverdue() && st.responseDueOn() != null && !st.responseDueOn().isAfter(soon);
                items.add(new AwaitingItem("STATEMENT", st.id(), st.responseDueOn(), st.responseOverdue(), soonDue, can,
                        null, new AwaitingStatement(st.sequenceNumber(), st.periodStart(), st.periodEnd()), null));
                total++;
                dueSoon += soonDue ? 1 : 0;
                overdue += st.responseOverdue() ? 1 : 0;
            }
        }
        if (p != null) {
            boolean can = actor.has(Permission.CREDITOR_PAYMENT_REPORT);
            for (ReportedPayment r : p) {
                items.add(new AwaitingItem("PAYMENT_REJECTED", r.id(), null, false, false, can, null, null,
                        new AwaitingPayment(r.paidOn(), r.amount(), r.rejectionReason(), r.decidedAt())));
                total++;
            }
        }
        // vencidos primeiro (prazo mais antigo), depois pelo prazo; sem prazo por último, os mais recentes primeiro
        items.sort(Comparator.comparing((AwaitingItem i) -> i.dueOn() == null)
                .thenComparing(AwaitingItem::dueOn, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing((AwaitingItem i) -> i.payment() == null ? null : i.payment().decidedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AwaitingItem::kind).thenComparing(AwaitingItem::id));
        return new Awaiting(total, dueSoon, overdue,
                new Counts(d == null ? 0 : d.total(), s == null ? 0 : s.size(), p == null ? 0 : p.size()),
                List.copyOf(items.subList(0, Math.min(AWAITING_ITEMS, items.size()))), List.copyOf(unavailable));
    }

    /**
     * Lê uma fonte. Só falha técnica vira seção indisponível: serviço temporariamente fora
     * ({@link TemporarilyUnavailableException}), banco ({@link DataAccessException}, inclusive o tempo limite da
     * consulta) e transação ({@link TransactionException}); o WARN leva só a fonte e a classe do erro. Recusa de
     * acesso, regra de negócio e erro de programação sobem como estão.
     */
    private <T> T source(String name, List<String> unavailable, Supplier<T> read) {
        try {
            return read.get();
        } catch (TemporarilyUnavailableException | DataAccessException | TransactionException e) {
            return unavailable(name, unavailable, e);
        }
    }

    private <T> T unavailable(String name, List<String> unavailable, RuntimeException e) {
        LOG.atWarn().addKeyValue("source", name).addKeyValue("error", e.getClass().getSimpleName())
                .log("Fonte do Início do portal do credor indisponível");
        metrics.sourceUnavailable(name.toLowerCase(java.util.Locale.ROOT));
        unavailable.add(name);
        return null;
    }
}
