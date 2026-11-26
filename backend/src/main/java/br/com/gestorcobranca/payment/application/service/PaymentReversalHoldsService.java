package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HoldFilter;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HoldView;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.ReleaseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases;
import br.com.gestorcobranca.payment.application.port.spi.IntegrationTokenLabels;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentReversal;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aba "Estornos em conferência" (H07 parte 3): lê a retenção pelo caso de uso do debt (escopo, permissões e minimização
 * lá) e acrescenta só o que é do payment — valor, data e canal do pagamento, token (com a permissão) e o estorno.
 */
@Service
@Transactional
class PaymentReversalHoldsService implements PaymentReversalHoldsUseCases {

    static final String RESOURCE = "reversal_hold";

    private final ReversalHoldUseCases holds;
    private final PaymentRepository payments;
    private final PaymentReversalRepository reversals;
    private final IntegrationTokenLabels tokenLabels;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final PaymentMetrics metrics;

    PaymentReversalHoldsService(ReversalHoldUseCases holds, PaymentRepository payments,
                                PaymentReversalRepository reversals, IntegrationTokenLabels tokenLabels,
                                AccessScope accessScope, CurrentActor currentActor, PaymentMetrics metrics) {
        this.holds = holds;
        this.payments = payments;
        this.reversals = reversals;
        this.tokenLabels = tokenLabels;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<HeldReversalItem> search(HoldFilter filter, PageQuery page) {
        requirePerson();
        PageResponse<HoldView> result = holds.search(filter, page);
        List<HeldReversalItem> items = items(result.content());
        metrics.reversalHoldQuery("list", items.isEmpty() ? "empty" : "ok");
        return new PageResponse<>(items, result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public HeldReversalItem get(UUID holdId) {
        requirePerson();
        return items(List.of(holds.get(holdId))).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HeldReversalItem> forPayment(UUID paymentId) {
        requirePerson();
        return items(holds.forPayment(paymentId));
    }

    @Override
    public HeldReversalItem release(UUID holdId, ReleaseCommand command) {
        requirePerson();
        accessScope.requirePermission(Permission.PAYMENT_REVIEW);
        HoldView released = holds.release(holdId, command);
        metrics.reversalHoldQuery("release", "ok");
        return items(List.of(released)).getFirst();
    }

    /** A pessoa do credor, a integração, o assistente e o devedor nunca leem nem liberam a retenção (RS-114, RS-119). */
    private void requirePerson() {
        Actor actor = currentActor.get();
        if (actor.type() != Actor.ActorType.USER) {
            throw accessScope.denyActorType(RESOURCE);
        }
    }

    private List<HeldReversalItem> items(List<HoldView> views) {
        if (views.isEmpty()) {
            return List.of();
        }
        Set<UUID> paymentIds = views.stream().map(HoldView::paymentId).collect(Collectors.toSet());
        Map<UUID, Payment> byId = payments.findAllByIdIn(paymentIds).stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));
        Map<UUID, PaymentReversal> reversalById = reversals.findByPaymentIdIn(paymentIds).stream()
                .collect(Collectors.toMap(PaymentReversal::getId, Function.identity(), (a, b) -> a));
        boolean showToken = currentActor.get().has(Permission.PUBLIC_API_TOKEN_VIEW);
        Set<UUID> tokenIds = showToken ? byId.values().stream().filter(Payment::isFromIntegration)
                .map(Payment::getInformedBy).collect(Collectors.toSet()) : Set.of();
        Map<UUID, IntegrationTokenLabels.TokenLabel> labels = tokenIds.isEmpty() ? Map.of() : tokenLabels.labels(tokenIds);
        return views.stream().map(v -> {
            Payment p = byId.get(v.paymentId());
            PaymentReversal r = reversalById.get(v.reversalId());
            return new HeldReversalItem(v, p == null ? null : p.getAmount().amount(), p == null ? null : p.getPaidAt(),
                    p == null ? null : p.informedChannel(),
                    p != null && showToken && p.isFromIntegration()
                            ? CreditorReportedPaymentsService.token(p.getInformedBy(), labels) : null,
                    r == null ? null : r.getSource().name(), r == null ? null : r.getReversedAt());
        }).toList();
    }
}
