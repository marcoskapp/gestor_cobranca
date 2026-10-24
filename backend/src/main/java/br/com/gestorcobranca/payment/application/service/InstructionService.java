package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases;
import br.com.gestorcobranca.payment.application.port.out.BoletoProvider;
import br.com.gestorcobranca.payment.application.port.out.PaymentLinkProvider;
import br.com.gestorcobranca.payment.application.service.TargetResolver.AgreementTarget;
import br.com.gestorcobranca.payment.application.service.TargetResolver.InstallmentInfo;
import br.com.gestorcobranca.payment.application.service.TargetResolver.TitleInfo;
import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.InstructionType;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PaymentSettings;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import br.com.gestorcobranca.payment.domain.service.PixIdentifiers;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class InstructionService implements PaymentInstructionUseCases {

    static final String RESOURCE = "payment_instruction";
    static final Set<InstructionStatus> OPEN = EnumSet.of(InstructionStatus.PENDING, InstructionStatus.ACTIVE);

    private final PaymentInstructionRepository instructions;
    private final PixAccountRepository accounts;
    private final TargetResolver resolver;
    private final PixGateway pixGateway;
    private final ObjectProvider<BoletoProvider> boletoProvider;
    private final ObjectProvider<PaymentLinkProvider> linkProvider;
    private final PaymentSettingsProvider settings;
    private final IdempotencyService idempotency;
    private final JobQueue jobs;
    private final PaymentRecorder recorder;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final PaymentMetrics metrics;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final Clock clock;

    InstructionService(PaymentInstructionRepository instructions, PixAccountRepository accounts, TargetResolver resolver,
                       PixGateway pixGateway, ObjectProvider<BoletoProvider> boletoProvider,
                       ObjectProvider<PaymentLinkProvider> linkProvider, PaymentSettingsProvider settings,
                       IdempotencyService idempotency, JobQueue jobs, PaymentRecorder recorder, AccessScope accessScope,
                       AuditTrail auditTrail, PaymentMetrics metrics, CurrentActor currentActor,
                       AccessLogger accessLogger, Clock clock) {
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.instructions = instructions;
        this.accounts = accounts;
        this.resolver = resolver;
        this.pixGateway = pixGateway;
        this.boletoProvider = boletoProvider;
        this.linkProvider = linkProvider;
        this.settings = settings;
        this.idempotency = idempotency;
        this.jobs = jobs;
        this.recorder = recorder;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public InstructionView create(CreateInstructionCommand command, String idempotencyKey) {
        accessScope.requirePermission(Permission.PAYMENT_INSTRUCTION_CREATE);
        Guard.require(command != null, "payment_instruction.required", "Dados da instrução obrigatórios");
        InstructionType type = PaymentEnums.parse(InstructionType.class, command.type(), "payment_instruction.type_invalid",
                "Tipo da instrução");
        requireProvider(type);
        boolean installment = command.installmentId() != null || command.agreementId() != null;
        Guard.require(installment ^ command.titleId() != null, "payment_instruction.target_required",
                "Informe a parcela do acordo (acordo e parcela) ou o título — um dos dois");
        String hash = String.join("|", type.name(), String.valueOf(command.agreementId()),
                String.valueOf(command.installmentId()), String.valueOf(command.titleId()),
                command.amount() == null ? "" : command.amount().stripTrailingZeros().toPlainString(),
                String.valueOf(command.dueDate()), String.valueOf(command.expiresInSeconds()));
        UUID id = idempotency.execute("payment.instruction", idempotencyKey, hash,
                () -> installment ? forInstallment(type, command) : forTitle(type, command));
        return PaymentViews.instruction(instructions.findById(id).orElseThrow());
    }

    private UUID forInstallment(InstructionType type, CreateInstructionCommand c) {
        Guard.require(c.agreementId() != null && c.installmentId() != null, "payment_instruction.installment_required",
                "Informe o acordo e a parcela");
        AgreementTarget agreement = resolver.agreement(c.agreementId());
        accessScope.requireCreditor(agreement.creditorId());
        requireOwnDebt("negotiation_agreement", agreement.agreementId(), agreement.debtorId());
        if (!agreement.active()) {
            throw new BusinessRuleException("payment_instruction.agreement_not_active",
                    "Acordo encerrado não recebe instrução de pagamento");
        }
        InstallmentInfo info = agreement.installment(c.installmentId());
        Guard.require(info.remaining().isPositive(), "payment_instruction.installment_paid",
                "Parcela sem saldo em aberto");
        Money amount = amount(c, info.remaining());
        Optional<PaymentInstruction> reusable = instructions.findByInstallmentIdAndStatusIn(c.installmentId(), OPEN)
                .stream().filter(i -> i.getType() == type && i.getAmount().equals(amount)).findFirst();
        if (reusable.isPresent()) {
            metrics.instruction(type.name(), "reused");
            return reusable.get().getId();
        }
        PixAccount account = account(type, agreement.creditorId());
        LocalDate dueDate = dueDate(type, c.dueDate(), info.dueDate());
        UUID instructionId = Ids.newId();
        Instant now = Instant.now(clock);
        PaymentInstruction instruction = PaymentInstruction.forInstallment(type, agreement.creditorId(),
                agreement.portfolioId(), agreement.debtorId(), agreement.caseId(), agreement.agreementId(), info.id(),
                info.number(), amount, dueDate, expiresAt(type, c.expiresInSeconds(), dueDate, now),
                account == null ? null : account.getId(), txid(type), c.channel(), now);
        return register(instruction);
    }

    private UUID forTitle(InstructionType type, CreateInstructionCommand c) {
        TitleInfo title = resolver.titles(List.of(c.titleId())).getFirst();
        accessScope.requireCreditor(title.creditorId());
        requireOwnDebt("debt_title", title.id(), title.debtorId());
        if (!title.payable()) {
            throw new BusinessRuleException("payment_instruction.title_unavailable", title.underAgreement()
                    ? "Título coberto por acordo em vigor: gere a instrução pela parcela do acordo"
                    : "Título sem saldo em aberto em cobrança");
        }
        Money amount = amount(c, title.openBalance());
        Optional<PaymentInstruction> reusable = instructions.findByTitleIdAndStatusIn(c.titleId(), OPEN).stream()
                .filter(i -> i.getType() == type && i.getAmount().equals(amount)).findFirst();
        if (reusable.isPresent()) {
            metrics.instruction(type.name(), "reused");
            return reusable.get().getId();
        }
        PixAccount account = account(type, title.creditorId());
        LocalDate dueDate = dueDate(type, c.dueDate(), null);
        Instant now = Instant.now(clock);
        PaymentInstruction instruction = PaymentInstruction.forTitle(type, title.creditorId(), title.portfolioId(),
                title.debtorId(), title.id(), amount, dueDate, expiresAt(type, c.expiresInSeconds(), dueDate, now),
                account == null ? null : account.getId(), txid(type), c.channel(), now);
        return register(instruction);
    }

    private UUID register(PaymentInstruction instruction) {
        PaymentInstruction saved = instructions.save(instruction);
        recorder.instructionCreated(saved);
        InstructionRegistrationJob.enqueue(jobs, saved.getId());
        return saved.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public InstructionView get(UUID instructionId) {
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        return PaymentViews.instruction(loadVisible(instructionId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InstructionView> search(InstructionFilter filter, PageQuery page) {
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        InstructionFilter f = filter == null ? new InstructionFilter(null, null, null, null, null) : filter;
        if (f.creditorId() != null) {
            accessScope.requireCreditor(f.creditorId());
        }
        Set<InstructionStatus> statuses = PaymentEnums.parseSet(InstructionStatus.class,
                f.status() == null ? List.of() : List.of(f.status()), "payment_instruction.status_invalid", "Situação");
        Actor actor = currentActor.get();
        UUID debtorId = actor.type() == Actor.ActorType.DEBTOR ? actor.id() : f.debtorId();
        return PageResponse.of(instructions.search(new PaymentInstructionRepository.Criteria(
                accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of()), f.creditorId(),
                debtorId, f.agreementId(), f.titleId(), statuses),
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))), PaymentViews::instruction);
    }

    @Override
    public InstructionView cancel(UUID instructionId, String reason) {
        accessScope.requirePermission(Permission.PAYMENT_INSTRUCTION_CREATE);
        PaymentInstruction instruction = instructions.findByIdForUpdate(instructionId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, instructionId));
        accessScope.requireVisible(RESOURCE, instructionId, instruction.getCreditorId());
        accessScope.requireCreditor(instruction.getCreditorId());
        cancel(instruction, reason, "manual");
        return PaymentViews.instruction(instruction);
    }

    /** Cancela a instrução aberta (manual ou reação a acordo/parcela/título encerrado). */
    void cancel(PaymentInstruction instruction, String reason, String trigger) {
        InstructionStatus before = instruction.getStatus();
        boolean registered = instruction.cancel(reason, Instant.now(clock));
        instructions.save(instruction);
        if (registered && instruction.isPix()) {
            InstructionCancellationJob.enqueue(jobs, instruction.getId());
        }
        metrics.instruction(instruction.getType().name(), "cancelled");
        auditTrail.record(AuditEntry.of("payment_instruction.cancelled", RESOURCE, instruction.getId())
                .creditor(instruction.getCreditorId()).change("status", before, InstructionStatus.CANCELLED)
                .reason(reason).meta("trigger", trigger));
    }

    private PaymentInstruction loadVisible(UUID id) {
        PaymentInstruction i = instructions.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, i.getCreditorId());
        requireOwnDebt(RESOURCE, id, i.getDebtorId());
        return i;
    }

    /**
     * Devedor autenticado no portal ({@code DEBTOR}): só instruções das próprias dívidas (defesa em
     * profundidade; o portal já passa somente o devedor da sessão). Recusa como 404 + {@code SCOPE_DENIED}.
     */
    private void requireOwnDebt(String resource, UUID resourceId, UUID debtorId) {
        Actor actor = currentActor.get();
        if (actor.type() == Actor.ActorType.DEBTOR && !actor.id().equals(debtorId)) {
            accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, actor, Map.of("resource", resource,
                    "resourceId", String.valueOf(resourceId), "reason", "portal_other_debtor")));
            throw new ResourceNotFoundException(resource, resourceId);
        }
    }

    private void requireProvider(InstructionType type) {
        boolean available = switch (type) {
            case PIX_IMMEDIATE, PIX_DUE -> true;
            case BOLETO -> boletoProvider.getIfAvailable() != null;
            case LINK -> linkProvider.getIfAvailable() != null;
        };
        if (!available) {
            metrics.instruction(type.name(), "provider_unavailable");
            throw new BusinessRuleException("payment_instruction.provider_unavailable",
                    "Provedor de %s ainda não definido (issue #12)".formatted(type == InstructionType.BOLETO ? "boleto"
                            : "link de pagamento"));
        }
        if (type == InstructionType.BOLETO || type == InstructionType.LINK) {
            // a porta existe, mas o registro e a baixa por esses provedores dependem do contrato do provedor escolhido
            throw new BusinessRuleException("payment_instruction.provider_unavailable",
                    "Registro de %s não habilitado (issue #12)".formatted(type));
        }
    }

    private PixAccount account(InstructionType type, UUID creditorId) {
        if (!PaymentInstruction.isPix(type)) {
            return null;
        }
        PixAccount account = accounts.findActiveForCreditor(creditorId).or(accounts::findActiveDefault)
                .orElseThrow(() -> new BusinessRuleException("payment_instruction.no_pix_account",
                        "Nenhuma conta Pix ativa para o credor ou para a assessoria"));
        if (!pixGateway.available(account.getProvider())) {
            throw new BusinessRuleException("pix_account.provider_unavailable",
                    "Provedor Pix %s indisponível neste ambiente".formatted(account.getProvider()));
        }
        return account;
    }

    private static Money amount(CreateInstructionCommand c, Money remaining) {
        if (c.amount() == null) {
            return remaining;
        }
        Money amount = Money.of(c.amount());
        Guard.require(amount.isPositive(), "payment_instruction.amount_invalid", "Valor deve ser positivo");
        Guard.require(!amount.isGreaterThan(remaining), "payment_instruction.amount_exceeds_balance",
                "Valor acima do saldo em aberto");
        return amount;
    }

    private LocalDate dueDate(InstructionType type, LocalDate requested, LocalDate installmentDue) {
        if (type != InstructionType.PIX_DUE) {
            return null;
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate due = requested != null ? requested : installmentDue != null ? installmentDue : today;
        if (requested == null && due.isBefore(today)) {
            due = today;
        }
        Guard.require(!due.isBefore(today), "payment_instruction.due_date_past", "Vencimento não pode estar no passado");
        return due;
    }

    private Instant expiresAt(InstructionType type, Integer expiresInSeconds, LocalDate dueDate, Instant now) {
        PaymentSettings.Values values = settings.current();
        if (type == InstructionType.PIX_DUE) {
            return dueDate.plusDays(values.pixDueValidityDays() + 1L).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        }
        int seconds = expiresInSeconds == null ? values.pixExpirationSeconds() : expiresInSeconds;
        Guard.require(seconds >= 300 && seconds <= 2_592_000, "payment_instruction.expiration_invalid",
                "Validade entre 300 segundos e 30 dias");
        return now.plusSeconds(seconds);
    }

    private static String txid(InstructionType type) {
        return PaymentInstruction.isPix(type) ? PixIdentifiers.txidFor(Ids.newId()) : null;
    }
}
