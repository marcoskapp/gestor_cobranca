package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases.BreakSuspensionSettings;
import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases;
import br.com.gestorcobranca.payment.application.service.TargetResolver.AgreementTarget;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.InstallmentBalance;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Situação de revisão dos pagamentos externos para quem negocia (menor privilégio: {@code AGREEMENT_VIEW},
 * sem evidência, observação nem referência). O motivo da recusa vai só para quem informou o pagamento
 * ou tem {@code PAYMENT_VIEW} (é texto livre do revisor). Somente leitura: não grava auditoria nem log de
 * negócio — nada muda e nenhum dado pessoal é exposto.
 */
@Service
@Transactional(readOnly = true)
class PaymentReviewStatusService implements PaymentReviewStatusUseCases {

    private final PaymentRepository payments;
    private final TargetResolver resolver;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final JsonCodec json;
    private final Clock clock;
    private final PaymentProperties.ReviewStatus config;
    private final AgreementBreakSuspensionUseCases breakSuspensions;

    PaymentReviewStatusService(PaymentRepository payments, TargetResolver resolver, AccessScope accessScope,
                               CurrentActor currentActor, JsonCodec json, Clock clock, PaymentProperties properties,
                               AgreementBreakSuspensionUseCases breakSuspensions) {
        this.breakSuspensions = breakSuspensions;
        this.payments = payments;
        this.resolver = resolver;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.json = json;
        this.clock = clock;
        this.config = properties.reviewStatus();
    }

    @Override
    public AgreementReviewStatus forAgreement(UUID agreementId) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        Guard.require(agreementId != null, "payment_review_status.agreement_required", "Acordo obrigatório");
        // lê o acordo pelo caso de uso do negotiation: confere permissão e escopo (404 fora dele)
        AgreementTarget agreement = resolver.agreement(agreementId);
        Instant since = rejectedSince();
        List<Payment> found = payments.findReviewStatusByAgreements(Set.of(agreement.creditorId()), List.of(agreementId),
                since);
        List<Payment> pending = pending(found);
        return new AgreementReviewStatus(agreementId, pending.size(), total(pending), items(pending),
                items(rejected(found)), since, agreement.active() ? coverage(agreement, pending) : List.of(),
                breakSuspension(List.of(agreementId)));
    }

    @Override
    public List<AgreementReviewBadge> forAgreements(Collection<UUID> agreementIds) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        Set<UUID> ids = agreementIds == null ? Set.of()
                : agreementIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Guard.require(ids.size() <= MAX_AGREEMENTS, "payment_review_status.too_many_agreements",
                "Consulte até " + MAX_AGREEMENTS + " acordos por vez");
        if (ids.isEmpty()) {
            return List.of();
        }
        // o credor do pagamento é o do acordo (conferido no registro): o escopo por credor basta, sem ler cada acordo
        Map<UUID, List<Payment>> byAgreement = payments.findReviewStatusByAgreements(allowedCreditors(), ids,
                rejectedSince()).stream().collect(Collectors.groupingBy(Payment::getTargetAgreementId,
                LinkedHashMap::new, Collectors.toList()));
        List<AgreementReviewBadge> badges = new ArrayList<>();
        for (UUID id : ids) {
            List<Payment> found = byAgreement.get(id);
            if (found == null) {
                continue;
            }
            List<Payment> pending = pending(found);
            List<Payment> rejected = found.stream().filter(p -> p.getStatus() == PaymentStatus.REJECTED).toList();
            badges.add(new AgreementReviewBadge(id, pending.size(), total(pending),
                    pending.isEmpty() ? null : pending.getLast().getInformedAt(), rejected.size(),
                    rejected.stream().map(Payment::getReviewedAt).max(Comparator.naturalOrder()).orElse(null)));
        }
        return badges;
    }

    @Override
    public DebtorReviewStatus forDebtor(UUID creditorId, UUID debtorId) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        Guard.require(creditorId != null && debtorId != null, "payment_review_status.debtor_required",
                "Credor e devedor obrigatórios");
        accessScope.requireCreditor(creditorId);
        Instant since = rejectedSince();
        List<Payment> found = payments.findReviewStatusByDebtor(creditorId, debtorId, since);
        List<Payment> pending = pending(found);
        List<Payment> rejected = rejected(found);
        List<UUID> agreementIds = java.util.stream.Stream.concat(pending.stream(), rejected.stream())
                .map(Payment::getTargetAgreementId).filter(Objects::nonNull).distinct().toList();
        return new DebtorReviewStatus(creditorId, debtorId, pending.size(), total(pending), items(pending),
                items(rejected), since, breakSuspension(agreementIds));
    }

    /** Suspensão da quebra dos acordos (lida do negotiation, com o escopo de quem consulta). */
    private BreakSuspensionInfo breakSuspension(List<UUID> agreementIds) {
        BreakSuspensionSettings settings = breakSuspensions.settings();
        return new BreakSuspensionInfo(settings.maxDays(), settings.graceDays(),
                agreementIds.isEmpty() ? List.of() : breakSuspensions.latest(agreementIds));
    }

    /**
     * Previsão das parcelas que os pendentes cobrem se aprovados na ordem do registro: mesma regra da
     * alocação (parcelas indicadas primeiro, o excedente às seguintes por número), sobre o saldo em aberto
     * descontadas as alocações em trânsito e os pendentes anteriores.
     */
    private List<CoverageView> coverage(AgreementTarget agreement, List<Payment> pending) {
        Map<UUID, InstallmentBalance> balances = new LinkedHashMap<>();
        agreement.balances().forEach(b -> balances.put(b.installmentId(), b));
        List<CoverageView> coverage = new ArrayList<>();
        for (Payment p : pending) {
            if (balances.isEmpty()) {
                break;
            }
            AllocationPlanner.Plan plan = AllocationPlanner.planAgreement(p.getAmount(), List.copyOf(balances.values()),
                    PaymentViews.targetItems(p, json));
            for (AllocationPlanner.Share share : plan.shares()) {
                coverage.add(new CoverageView(share.targetId(), p.getId(), share.amount().amount()));
                InstallmentBalance current = balances.get(share.targetId());
                Money left = current.remaining().minus(share.amount());
                if (left.isPositive()) {
                    balances.put(current.installmentId(), new InstallmentBalance(current.installmentId(), current.number(), left));
                } else {
                    balances.remove(current.installmentId());
                }
            }
        }
        return coverage;
    }

    private List<ReviewItem> items(List<Payment> list) {
        Actor actor = currentActor.get();
        boolean paymentViewer = actor.has(Permission.PAYMENT_VIEW);
        return list.stream().map(p -> {
            boolean showReason = paymentViewer || Objects.equals(p.getInformedBy(), actor.id());
            String reason = p.getStatus() == PaymentStatus.REJECTED ? p.getReviewNote() : null;
            return new ReviewItem(p.getId(), p.getStatus().name(), p.getAmount().amount(), p.getPaidAt(),
                    PaymentEnums.name(p.getMethod()), p.getInformedBy(), p.getInformedAt(),
                    PaymentEnums.name(p.getTargetKind()), p.getTargetAgreementId(), PaymentViews.targetItems(p, json),
                    p.getReviewedBy(), p.getReviewedAt(), showReason ? reason : null, reason != null && !showReason);
        }).toList();
    }

    private static List<Payment> pending(List<Payment> found) {
        return found.stream().filter(p -> p.getStatus() == PaymentStatus.INFORMED).toList();
    }

    /** Recusados do mais recente ao mais antigo, até o máximo configurado. */
    private List<Payment> rejected(List<Payment> found) {
        return found.stream().filter(p -> p.getStatus() == PaymentStatus.REJECTED)
                .sorted(Comparator.comparing(Payment::getReviewedAt).reversed().thenComparing(Payment::getId))
                .limit(config.maxRejected()).toList();
    }

    private static BigDecimal total(List<Payment> list) {
        return list.stream().map(Payment::getAmount).reduce(Money.ZERO, Money::plus).amount();
    }

    private Instant rejectedSince() {
        return Instant.now(clock).minus(config.rejectedWindow());
    }

    private Set<UUID> allowedCreditors() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
    }
}
