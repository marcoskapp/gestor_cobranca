package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases.BreakSuspensionView;
import br.com.gestorcobranca.payment.application.port.out.PaymentPendingWorkQueries;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pendências do módulo: revisão por segunda pessoa dos pagamentos externos (quem informou não vê o
 * próprio — não pode aprová-lo) e divergências de conciliação em aberto. Mesmos critérios de
 * {@code PaymentService#reviewQueue} e {@code ReconciliationService#divergences}.
 */
@Component
class PaymentPendingWork implements PendingWorkSource {

    static final String EXTERNAL_REVIEW = "payment.external_review";
    static final String DIVERGENCES = "payment.divergences";

    private final PaymentPendingWorkQueries queries;
    private final AccessScope accessScope;
    private final AgreementBreakSuspensionUseCases breakSuspensions;
    private final Clock clock;

    PaymentPendingWork(PaymentPendingWorkQueries queries, AccessScope accessScope,
                       AgreementBreakSuspensionUseCases breakSuspensions, Clock clock) {
        this.queries = queries;
        this.accessScope = accessScope;
        this.breakSuspensions = breakSuspensions;
        this.clock = clock;
    }

    @Override
    public String id() {
        return "payment";
    }

    @Override
    public String label() {
        return "Pagamentos";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.PAYMENT_REVIEW) || actor.has(Permission.RECONCILIATION_MANAGE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.PAYMENT_REVIEW)) {
            items.add(PendingWorkItem.of(EXTERNAL_REVIEW, "Pagamentos informados para aprovar",
                            withBreakDeadlines(actor, scope, queries.externalReview(scope, actor.id())))
                    .hint("Confira o comprovante e aprove ou recuse. Enquanto aguarda, a quebra do acordo fica suspensa.")
                    .route("/pagamentos/aprovacoes").build());
        }
        if (actor.has(Permission.RECONCILIATION_MANAGE)) {
            items.add(PendingWorkItem.of(DIVERGENCES, "Divergências de conciliação", queries.openDivergences(scope))
                    .hint("Vincule ao pagamento, aceite ou descarte.")
                    .route("/pagamentos/divergencias").param("situacao", "OPEN").build());
        }
        return items;
    }

    /**
     * Destaque pelo prazo da suspensão da quebra (decisão 546): vencido = acordo cuja suspensão terminou pelo
     * prazo máximo (ou já passou dele) com pagamento ainda em revisão; vence hoje = último dia da suspensão.
     * Sem {@code AGREEMENT_VIEW}, fica a contagem simples.
     */
    private PendingWorkCount withBreakDeadlines(Actor actor, Set<UUID> scope, PendingWorkCount base) {
        if (base.isEmpty() || !actor.has(Permission.AGREEMENT_VIEW)) {
            return base;
        }
        Map<UUID, Long> byAgreement = queries.externalReviewByAgreement(scope, actor.id());
        if (byAgreement.isEmpty()) {
            return base;
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        long overdue = 0;
        long dueToday = 0;
        LocalDate next = null;
        List<UUID> ids = List.copyOf(byAgreement.keySet());
        for (int from = 0; from < ids.size(); from += AgreementBreakSuspensionUseCases.MAX_AGREEMENTS) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + AgreementBreakSuspensionUseCases.MAX_AGREEMENTS));
            for (BreakSuspensionView s : breakSuspensions.latest(chunk)) {
                long count = byAgreement.getOrDefault(s.agreementId(), 0L);
                boolean active = "ACTIVE".equals(s.status());
                if ("DEADLINE".equals(s.endReason()) || (active && today.isAfter(s.suspendedUntil()))) {
                    overdue += count;
                } else if (active && today.equals(s.suspendedUntil())) {
                    dueToday += count;
                }
                if (active && (next == null || s.suspendedUntil().isBefore(next))) {
                    next = s.suspendedUntil();
                }
            }
        }
        // as duas consultas não são atômicas: as contagens nunca passam do total
        overdue = Math.min(overdue, base.total());
        dueToday = Math.min(dueToday, base.total() - overdue);
        return new PendingWorkCount(base.total(), overdue, dueToday, base.oldestAt(), next, base.singleId());
    }
}
