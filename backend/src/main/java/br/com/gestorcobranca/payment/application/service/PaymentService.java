package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases;
import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.out.ExternalPaymentLocks;
import br.com.gestorcobranca.payment.application.service.AllocationService.Destination;
import br.com.gestorcobranca.payment.application.service.AllocationService.Result;
import br.com.gestorcobranca.payment.application.service.TargetResolver.AgreementTarget;
import br.com.gestorcobranca.payment.application.service.TargetResolver.TitleInfo;
import br.com.gestorcobranca.payment.domain.model.AllocationOrigin;
import br.com.gestorcobranca.payment.domain.model.ExternalOrigin;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentMethod;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.model.ReversalReason;
import br.com.gestorcobranca.payment.domain.model.ReversalSource;
import br.com.gestorcobranca.payment.domain.model.TargetKind;
import br.com.gestorcobranca.payment.domain.model.TitleStrategy;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import br.com.gestorcobranca.payment.domain.service.ExternalReviewPolicy;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class PaymentService implements PaymentUseCases {

    private static final String RESOURCE = AllocationService.RESOURCE;

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final PaymentReversalRepository reversals;
    private final TargetResolver resolver;
    private final AllocationService allocationService;
    private final PaymentConfirmation confirmation;
    private final PaymentReversals paymentReversals;
    private final PaymentRecorder recorder;
    private final EvidenceStore evidenceStore;
    private final PaymentSettingsProvider settings;
    private final IdempotencyService idempotency;
    private final FileStorage fileStorage;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final PaymentMetrics metrics;
    private final JsonCodec json;
    private final Clock clock;
    private final ExternalPaymentLocks externalLocks;
    private final PaymentProperties properties;
    private final AgreementBreakSuspensionUseCases breakSuspensions;

    PaymentService(PaymentRepository payments, PaymentAllocationRepository allocations, PaymentReversalRepository reversals,
                   TargetResolver resolver, AllocationService allocationService, PaymentConfirmation confirmation,
                   PaymentReversals paymentReversals, PaymentRecorder recorder, EvidenceStore evidenceStore,
                   PaymentSettingsProvider settings, IdempotencyService idempotency, FileStorage fileStorage,
                   AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger, PaymentMetrics metrics,
                   JsonCodec json, Clock clock, ExternalPaymentLocks externalLocks, PaymentProperties properties,
                   AgreementBreakSuspensionUseCases breakSuspensions) {
        this.payments = payments;
        this.allocations = allocations;
        this.reversals = reversals;
        this.resolver = resolver;
        this.allocationService = allocationService;
        this.confirmation = confirmation;
        this.paymentReversals = paymentReversals;
        this.recorder = recorder;
        this.evidenceStore = evidenceStore;
        this.settings = settings;
        this.idempotency = idempotency;
        this.fileStorage = fileStorage;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
        this.externalLocks = externalLocks;
        this.properties = properties;
        this.breakSuspensions = breakSuspensions;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView get(UUID paymentId) {
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        Payment p = loadVisible(paymentId);
        PaymentView view = view(p);
        return p.getStatus() == PaymentStatus.INFORMED ? withBreakSuspensions(List.of(view)).getFirst() : view;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentView> search(PaymentFilter filter, PageQuery page) {
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        PaymentFilter f = filter == null ? new PaymentFilter(null, null, null, null, null, null, null, null) : filter;
        if (f.creditorId() != null) {
            accessScope.requireCreditor(f.creditorId());
        }
        Set<PaymentStatus> statuses = PaymentEnums.parseSet(PaymentStatus.class,
                f.status() == null ? List.of() : List.of(f.status()), "payment.status_invalid", "Situação");
        String source = PaymentEnums.name(PaymentEnums.parseOptional(br.com.gestorcobranca.payment.domain.model.PaymentSource.class,
                f.source(), "payment.source_invalid", "Origem"));
        String method = PaymentEnums.name(PaymentEnums.parseOptional(PaymentMethod.class, f.method(),
                "payment.method_invalid", "Meio de pagamento"));
        Page<Payment> result = payments.search(new PaymentRepository.Criteria(allowedCreditors(), f.creditorId(),
                f.debtorId(), statuses, source, method, f.paidFrom(), f.paidTo(), Boolean.TRUE.equals(f.withBalance()),
                null, f.agreementId()), page.toPageable(Sort.by(Sort.Direction.DESC, "paidAt").and(Sort.by("id"))));
        return views(result);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView findByAllocation(UUID allocationId) {
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        PaymentAllocation allocation = allocations.findById(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException("payment_allocation", allocationId));
        Payment p = payments.findById(allocation.getPaymentId()).orElseThrow();
        accessScope.requireVisible("payment_allocation", allocationId, p.getCreditorId());
        return view(p);
    }

    @Override
    public PaymentView registerExternal(ExternalPaymentCommand command, EvidenceUpload evidence, String idempotencyKey) {
        accessScope.requirePermission(Permission.PAYMENT_REGISTER_EXTERNAL);
        Guard.require(command != null, "payment.required", "Dados do pagamento obrigatórios");
        Guard.require(command.creditorId() != null && command.debtorId() != null, "payment.target_required",
                "Credor e devedor obrigatórios");
        accessScope.requireCreditor(command.creditorId());
        Guard.require(command.amount() != null && command.amount().signum() > 0, "payment.amount_invalid",
                "Valor do pagamento deve ser positivo");
        Guard.require(command.paidAt() != null, "payment.paid_at_required", "Data do pagamento obrigatória");
        PaymentMethod method = PaymentEnums.parse(PaymentMethod.class, command.method(), "payment.method_invalid",
                "Meio de pagamento");
        ExternalOrigin origin = PaymentEnums.parse(ExternalOrigin.class, command.origin(), "payment.origin_invalid",
                "Fonte do pagamento");
        Guard.require(command.target() != null, "payment.target_required",
                "Informe o destino do pagamento (parcelas de acordo ou títulos)");
        Destination destination = validatedDestination(command.target(), command.creditorId(), command.debtorId());
        UUID id = idempotency.execute("payment.external", idempotencyKey, hash(command), () -> {
            Payment.Evidence stored = evidenceStore.store(evidence);
            Money amount = Money.of(command.amount());
            Instant now = Instant.now(clock);
            ExternalReviewPolicy.Decision decision = externalReview(command, amount, now);
            boolean reviewRequired = decision.reviewRequired();
            Payment p = payments.save(Payment.informed(method, command.creditorId(), command.debtorId(), amount,
                    command.paidAt(), origin, command.externalReference(), command.note(),
                    new Payment.Target(destination.kind(), destination.agreementId(), json.toJson(destination.items()),
                            destination.strategy()), stored, currentActor.actorId(), reviewRequired, now));
            recorder.informed(p, decision.reason() == null ? null : decision.reason().name());
            metrics.externalReview(decision.reason() == null ? "AUTO" : decision.reason().name());
            if (!reviewRequired) {
                confirmation.confirmed(p, null, "auto_low_risk");
            }
            return p.getId();
        });
        return view(payments.findById(id).orElseThrow());
    }

    /**
     * Limite por pagamento da assessoria e limites acumulados na janela (por devedor/credor e por quem
     * informa), sob lock para que registros simultâneos não escapem da soma.
     */
    private ExternalReviewPolicy.Decision externalReview(ExternalPaymentCommand command, Money amount, Instant now) {
        UUID informer = currentActor.actorId();
        externalLocks.lockExternalRegistration(currentActor.tenantId(), informer, command.creditorId(), command.debtorId());
        PaymentProperties.ExternalReview config = properties.externalReview();
        Money perPayment = settings.current().externalReviewThreshold();
        Instant since = now.minus(config.window());
        return ExternalReviewPolicy.decide(amount,
                payments.sumExternalInformedForDebtor(command.creditorId(), command.debtorId(), since),
                payments.sumExternalInformedBy(informer, since),
                new ExternalReviewPolicy.Limits(perPayment, perPayment.times(config.destinationFactor()),
                        perPayment.times(config.informerFactor())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentView> reviewQueue(PageQuery page) {
        accessScope.requirePermission(Permission.PAYMENT_REVIEW);
        Page<Payment> result = payments.search(new PaymentRepository.Criteria(allowedCreditors(), null, null,
                Set.of(PaymentStatus.INFORMED), null, null, null, null, false, currentActor.actorId(), null),
                page.toPageable(Sort.by("informedAt").and(Sort.by("id"))));
        PageResponse<PaymentView> views = views(result);
        return new PageResponse<>(withBreakSuspensions(views.content()), views.page(), views.size(),
                views.totalElements(), views.totalPages());
    }

    /**
     * Pagamentos em revisão com acordo de destino recebem a suspensão da quebra mais recente do acordo (uma
     * consulta por página). Só para quem tem {@code AGREEMENT_VIEW} (o negotiation confere o escopo).
     */
    private List<PaymentView> withBreakSuspensions(List<PaymentView> views) {
        List<UUID> agreementIds = views.stream().filter(v -> PaymentStatus.INFORMED.name().equals(v.status())
                        && v.requestedTarget() != null && v.requestedTarget().agreementId() != null)
                .map(v -> v.requestedTarget().agreementId()).distinct().toList();
        if (agreementIds.isEmpty() || !currentActor.get().has(Permission.AGREEMENT_VIEW)) {
            return views;
        }
        Map<UUID, AgreementBreakSuspensionUseCases.BreakSuspensionView> byAgreement = breakSuspensions.latest(agreementIds)
                .stream().collect(Collectors.toMap(AgreementBreakSuspensionUseCases.BreakSuspensionView::agreementId,
                        Function.identity()));
        return views.stream().map(v -> v.requestedTarget() == null || !PaymentStatus.INFORMED.name().equals(v.status())
                ? v : v.withBreakSuspension(byAgreement.get(v.requestedTarget().agreementId()))).toList();
    }

    @Override
    public PaymentView approve(UUID paymentId, String note) {
        accessScope.requirePermission(Permission.PAYMENT_REVIEW);
        Payment p = lockForCommand(paymentId);
        Instant now = Instant.now(clock);
        p.approve(currentActor.actorId(), note, now);
        metrics.review("APPROVED", Duration.between(p.getInformedAt(), now));
        confirmation.confirmed(p, null, "review");
        reviewClosed(p);
        return view(p);
    }

    @Override
    public PaymentView reject(UUID paymentId, String note) {
        accessScope.requirePermission(Permission.PAYMENT_REVIEW);
        Payment p = lockForCommand(paymentId);
        Instant now = Instant.now(clock);
        p.reject(currentActor.actorId(), note, now);
        payments.save(p);
        metrics.review("REJECTED", Duration.between(p.getInformedAt(), now));
        recorder.rejected(p);
        reviewClosed(p);
        return view(p);
    }

    /**
     * Revisão concluída: o negotiation encerra, na mesma transação, a suspensão da quebra do acordo se não
     * restar outro pagamento em revisão (decisões 540–549; idempotente).
     */
    private void reviewClosed(Payment p) {
        if (p.getTargetKind() == TargetKind.AGREEMENT && p.getTargetAgreementId() != null) {
            payments.save(p);
            breakSuspensions.reconcile(p.getTargetAgreementId());
        }
    }

    @Override
    public PaymentView reverse(UUID paymentId, ReverseCommand command) {
        accessScope.requirePermission(Permission.PAYMENT_REVERSE);
        Guard.require(command != null, "reversal.required", "Dados do estorno obrigatórios");
        ReversalReason reason = PaymentEnums.parse(ReversalReason.class, command.reason(), "reversal.reason_invalid",
                "Motivo do estorno");
        Payment p = lockForCommand(paymentId);
        paymentReversals.reverse(p, reason, ReversalSource.MANUAL, null, command.note());
        return view(p);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentView> pendingReallocation(PageQuery page) {
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        Page<UUID> ids = payments.findPendingReallocationIds(allowedCreditors(), page.toPageable());
        Map<UUID, Payment> loaded = payments.findAllByIdIn(ids.getContent()).stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));
        List<Payment> ordered = ids.getContent().stream().map(loaded::get).filter(Objects::nonNull).toList();
        return views(new PageImpl<>(ordered, ids.getPageable(), ids.getTotalElements()));
    }

    @Override
    public PaymentView reallocate(UUID paymentId, ReallocateCommand command) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        Guard.require(command != null && command.amount() != null && command.amount().signum() > 0,
                "reallocation.amount_invalid", "Valor a realocar deve ser positivo");
        Guard.require(command.note() != null && !command.note().isBlank() && command.note().length() <= 1000,
                "reallocation.note_required", "Justificativa obrigatória (até 1000 caracteres)");
        Guard.require(command.target() != null ^ (command.refundReference() != null && !command.refundReference().isBlank()),
                "reallocation.target_required", "Informe um novo destino ou a referência da devolução (um dos dois)");
        Payment p = lockForCommand(paymentId);
        Guard.requireState(p.isEffective(), "payment.not_confirmed", "Somente pagamento confirmado pode ser realocado");
        Money amount = Money.of(command.amount());
        PaymentAllocation source = null;
        if (command.sourceAllocationId() != null) {
            source = allocations.findById(command.sourceAllocationId())
                    .filter(a -> a.getPaymentId().equals(p.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("payment_allocation", command.sourceAllocationId()));
            Guard.require(source.getKind() == TargetKind.AGREEMENT, "reallocation.source_invalid",
                    "Somente alocações a acordo têm valor não aplicado a realocar");
            allocationService.release(p, source, amount);
        }
        Guard.require(!amount.isGreaterThan(p.unallocated()), "reallocation.amount_exceeds_available",
                "Valor acima do disponível para realocação");
        UUID sourceId = source == null ? null : source.getId();
        List<PaymentAllocation> created;
        if (command.target() != null) {
            Destination destination = validatedDestination(command.target(), p.getCreditorId(), p.getDebtorId());
            Result result = allocationService.allocate(p, destination, amount, AllocationOrigin.REALLOCATION, sourceId);
            if (result.allocations().isEmpty()) {
                throw new BusinessRuleException("reallocation.target_unavailable",
                        "O destino não tem saldo em aberto para receber o valor");
            }
            created = result.allocations();
        } else {
            created = List.of(allocationService.refund(p, amount, command.refundReference(), sourceId));
        }
        payments.save(p);
        for (PaymentAllocation allocation : created) {
            recorder.reallocated(p, source, allocation, allocation.getAmount());
        }
        return view(p);
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContent openEvidence(UUID paymentId) {
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        Payment p = loadVisible(paymentId);
        if (p.getEvidenceKey() == null) {
            throw new ResourceNotFoundException("payment_evidence", paymentId);
        }
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(), Map.of("resource", RESOURCE,
                "resourceId", paymentId.toString(), "category", FileCategory.EVIDENCE.name())));
        return new EvidenceContent(p.getEvidenceName(), p.getEvidenceContentType(),
                p.getEvidenceSize() == null ? -1 : p.getEvidenceSize(), fileStorage.open(p.getEvidenceKey()));
    }

    /** Valida o destino informado (credor, devedor, parcelas do acordo) e converte para a alocação. */
    private Destination validatedDestination(TargetInput target, UUID creditorId, UUID debtorId) {
        TargetKind kind = PaymentEnums.parse(TargetKind.class, target.kind(), "payment.target_kind_invalid",
                "Tipo de destino");
        Guard.require(kind != TargetKind.REFUND, "payment.target_kind_invalid", "Destino deve ser AGREEMENT ou TITLES");
        TitleStrategy strategy = PaymentEnums.parseOptional(TitleStrategy.class, target.strategy(),
                "payment.target_strategy_invalid", "Estratégia");
        Destination destination = new Destination(kind, target.agreementId(), target.itemIds(),
                kind == TargetKind.TITLES ? (strategy == null ? TitleStrategy.OLDEST_FIRST : strategy) : null);
        if (kind == TargetKind.AGREEMENT) {
            AgreementTarget agreement = resolver.agreement(target.agreementId());
            requireParties(agreement.creditorId(), agreement.debtorId(), creditorId, debtorId);
            destination.items().forEach(agreement::installment);
        } else {
            for (TitleInfo title : resolver.titles(destination.items())) {
                requireParties(title.creditorId(), title.debtorId(), creditorId, debtorId);
            }
        }
        return destination;
    }

    private static void requireParties(UUID targetCreditor, UUID targetDebtor, UUID creditorId, UUID debtorId) {
        if (!targetCreditor.equals(creditorId) || !targetDebtor.equals(debtorId)) {
            throw new BusinessRuleException("allocation.target_mismatch",
                    "O destino não pertence ao credor e devedor do pagamento");
        }
    }

    private Payment loadVisible(UUID paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, paymentId));
        accessScope.requireVisible(RESOURCE, paymentId, p.getCreditorId());
        return p;
    }

    /** Bloqueia antes de carregar (sem versão desatualizada no contexto de persistência) e aplica o escopo. */
    private Payment lockForCommand(UUID paymentId) {
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, paymentId));
        accessScope.requireVisible(RESOURCE, paymentId, p.getCreditorId());
        accessScope.requireCreditor(p.getCreditorId());
        return p;
    }

    private Set<UUID> allowedCreditors() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
    }

    private PageResponse<PaymentView> views(Page<Payment> page) {
        List<UUID> ids = page.getContent().stream().map(Payment::getId).toList();
        Map<UUID, List<PaymentAllocation>> byPayment = ids.isEmpty() ? Map.of() : allocations.findByPaymentIdIn(ids)
                .stream().collect(Collectors.groupingBy(PaymentAllocation::getPaymentId));
        return PageResponse.of(page, p -> PaymentViews.payment(p, byPayment.getOrDefault(p.getId(), List.of()).stream()
                .sorted(java.util.Comparator.comparing(PaymentAllocation::getCreatedAt)).toList(),
                p.getStatus() == PaymentStatus.REVERSED ? reversals.findByPaymentId(p.getId()) : java.util.Optional.empty(),
                targetItems(p)));
    }

    private PaymentView view(Payment p) {
        return PaymentViews.payment(p, allocations.findByPaymentIdOrderByCreatedAtAsc(p.getId()),
                reversals.findByPaymentId(p.getId()), targetItems(p));
    }

    /** Ids do destino solicitado (JSON gravado no registro do pagamento externo). */
    private List<UUID> targetItems(Payment p) {
        return PaymentViews.targetItems(p, json);
    }

    private String hash(ExternalPaymentCommand c) {
        String canonical = String.join("|", String.valueOf(c.creditorId()), String.valueOf(c.debtorId()),
                c.amount().stripTrailingZeros().toPlainString(), String.valueOf(c.paidAt()), String.valueOf(c.method()),
                String.valueOf(c.origin()), String.valueOf(c.externalReference()), String.valueOf(c.target()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
