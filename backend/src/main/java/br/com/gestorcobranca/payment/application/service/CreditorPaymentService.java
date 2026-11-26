package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.application.port.in.allocation.AgreementAllocationQueries;
import br.com.gestorcobranca.negotiation.application.port.in.allocation.AgreementAllocationQueries.AllocationAgreement;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases;
import br.com.gestorcobranca.payment.application.port.out.ExternalPaymentLocks;
import br.com.gestorcobranca.payment.application.service.TargetResolver.AgreementTarget;
import br.com.gestorcobranca.payment.application.service.TargetResolver.InstallmentInfo;
import br.com.gestorcobranca.payment.application.service.TargetResolver.TitleInfo;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentMethod;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.model.TargetKind;
import br.com.gestorcobranca.payment.domain.model.TitleStrategy;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.persistence.CreditorPortalReadTimeout;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.StepUpProofs;
import br.com.gestorcobranca.shared.security.StepUpPurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pagamento direto informado pela pessoa do credor no portal (ADR 0011, fatia 6). Só números de título vêm do
 * cliente: o devedor e, se os títulos estão num acordo ativo, o próprio acordo e as parcelas em aberto são derivados
 * aqui (RS-34; decisão 1438). Todo informe fica em análise ({@link Payment#informedByCreditor}), com limite de 20 em
 * análise por credor sob trava (RS-36), recusa de duplicidade entre pessoas do credor, idempotência obrigatória no
 * escopo da pessoa (RS-35) e o comprovante da nova verificação do MFA conferido na mesma transação (SEG-F07).
 */
@Service
@Transactional
class CreditorPaymentService implements CreditorPaymentUseCases {

    static final int MAX_IN_REVIEW = 20;
    static final int MAX_TITLES = 20;
    private static final int LIST_LIMIT = 200;
    private static final String RESOURCE = AllocationService.RESOURCE;
    private static final String IDEMPOTENCY_SCOPE = "payment.creditor_report";
    private static final Pattern NUMBER = Pattern.compile("[A-Za-z0-9._/-]{1,60}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,80}");
    private static final Set<String> METHODS = Set.of("PIX", "BOLETO", "CARD", "TRANSFER", "CASH", "OTHER");

    private final DebtTitleUseCases titles;
    private final TargetResolver resolver;
    private final AgreementAllocationQueries agreements;
    private final PaymentRepository payments;
    private final EvidenceStore evidenceStore;
    private final CreditorEvidenceScreening screening;
    private final ExternalPaymentLocks locks;
    private final IdempotencyService idempotency;
    private final StepUpProofs stepUpProofs;
    private final PaymentRecorder recorder;
    private final PaymentMetrics metrics;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final Clock clock;
    private final CreditorPortalReadTimeout readTimeout;

    CreditorPaymentService(DebtTitleUseCases titles, TargetResolver resolver, AgreementAllocationQueries agreements,
                           PaymentRepository payments, EvidenceStore evidenceStore, CreditorEvidenceScreening screening,
                           ExternalPaymentLocks locks, IdempotencyService idempotency, StepUpProofs stepUpProofs,
                           PaymentRecorder recorder, PaymentMetrics metrics, AuditTrail auditTrail, JsonCodec json,
                           AccessScope accessScope, CurrentActor currentActor, Clock clock,
                           CreditorPortalReadTimeout readTimeout) {
        this.titles = titles;
        this.resolver = resolver;
        this.agreements = agreements;
        this.payments = payments;
        this.evidenceStore = evidenceStore;
        this.screening = screening;
        this.locks = locks;
        this.idempotency = idempotency;
        this.stepUpProofs = stepUpProofs;
        this.recorder = recorder;
        this.metrics = metrics;
        this.auditTrail = auditTrail;
        this.json = json;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.clock = clock;
        this.readTimeout = readTimeout;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorTitleMatch> findTitles(String number) {
        UUID creditorId = creditor(Permission.CREDITOR_PAYMENT_REPORT);
        String n = number == null ? "" : number.strip();
        if (!NUMBER.matcher(n).matches()) {
            return List.of();
        }
        Optional<TitleView> found = titles.findByExternalId(creditorId, n);
        if (found.isEmpty() || !found.get().creditorId().equals(creditorId)) {
            return List.of();
        }
        TitleInfo info = resolver.titles(List.of(found.get().id())).getFirst();
        if (!info.openForPayment()) {
            return List.of();
        }
        return List.of(new CreditorTitleMatch(info.externalId(), info.installmentNumber(), info.dueDate(),
                info.openBalance().amount(), info.underAgreement()));
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public VerifiedEvidence screen(EvidenceUpload upload) {
        creditor(Permission.CREDITOR_PAYMENT_REPORT);
        return screening.screen(upload);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireCanReport(ReportCommand command, VerifiedEvidence evidence, String idempotencyKey) {
        UUID creditorId = creditor(Permission.CREDITOR_PAYMENT_REPORT);
        validate(command);
        if (replay(command, evidence, idempotencyKey).isPresent()) {
            return; // repetição: devolve o informe já gravado, mesmo com o limite cheio ou o saldo já baixado
        }
        resolve(creditorId, command);
        if (payments.countCreditorPortalInReview(creditorId) >= MAX_IN_REVIEW) {
            throw limitReached();
        }
    }

    @Override
    public ReportedPayment report(ReportCommand command, VerifiedEvidence evidence, String idempotencyKey,
                                  String stepUpProof) {
        UUID creditorId = creditor(Permission.CREDITOR_PAYMENT_REPORT);
        Guard.require(idempotencyKey != null && KEY.matcher(idempotencyKey).matches(), "payment.idempotency_key_invalid",
                "Envio sem identificador válido. Atualize a página e tente de novo.");
        Guard.require(evidence != null && evidence.content() != null, "payment.evidence_required",
                "Anexe o comprovante do pagamento");
        validate(command);
        Actor actor = currentActor.get();
        // comprovante opaco da nova verificação, emitido nesta transação para esta pessoa, sessão e informe (F07)
        Instant mfaVerifiedAt = stepUpProofs.consume(stepUpProof, StepUpPurpose.PAYMENT_REPORT,
                CreditorPaymentUseCases.subjectRef(command, evidence.sha256()));
        locks.lockCreditorPortalReports(actor.tenantId(), creditorId);
        Optional<UUID> replayed = replay(command, evidence, idempotencyKey);
        if (replayed.isPresent()) {
            return item(payments.findById(replayed.get()).orElseThrow());
        }
        Resolved resolved = resolve(creditorId, command);
        Money amount = Money.of(command.amount());
        Instant paidAt = command.paidOn().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        UUID id = idempotency.execute(IDEMPOTENCY_SCOPE, scopedKey(idempotencyKey),
                requestHash(resolved.numbers(), command, evidence.sha256()), () -> {
                    // só ao criar (a repetição com a mesma chave devolve o mesmo informe), sob a trava do credor
                    if (payments.countCreditorPortalInReview(creditorId) >= MAX_IN_REVIEW) {
                        metrics.creditorPortalReport("rejected_limit");
                        throw limitReached();
                    }
                    if (payments.existsCreditorPortalDuplicate(creditorId, resolved.numbers(), amount, paidAt)) {
                        metrics.creditorPortalReport("rejected_duplicate");
                        throw new ConflictException("payment.creditor_report_duplicate",
                                "Já existe um informe em análise com esses títulos, valor e data. Acompanhe a análise da assessoria.");
                    }
                    Payment.Evidence stored = evidenceStore.storeVerified(evidence.fileName(), evidence.contentType(),
                            evidence.content(), evidence.sha256());
                    Instant now = Instant.now(clock);
                    Payment p = payments.save(Payment.informedByCreditor(
                            PaymentEnums.parse(PaymentMethod.class, command.method(), "payment.method_invalid", "Meio"),
                            creditorId, resolved.debtorId(), amount, paidAt, command.reference(), command.note(),
                            resolved.target(), stored, resolved.numbers(), evidence.verdict(), evidence.engine(),
                            evidence.scannedAt(), actor.id(), now));
                    recorder.informed(p, Payment.REVIEW_REASON_CREDITOR_PORTAL);
                    auditTrail.record(AuditEntry.of("payment.creditor_portal_reported", RESOURCE, p.getId())
                            .creditor(creditorId)
                            .created("titles", resolved.titleCount())
                            .created("targetKind", resolved.target().kind())
                            .meta("sessionId", actor.sessionId())
                            .meta("mfaVerifiedAt", mfaVerifiedAt)
                            .meta("evidenceScanVerdict", evidence.verdict())
                            .meta("evidenceScanEngine", evidence.engine()));
                    metrics.creditorPortalReport("reported");
                    return p.getId();
                });
        return item(payments.findById(id).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportedPayment> reported() {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        return payments.findCreditorPortalReports(creditorId, LIST_LIMIT).stream().map(CreditorPaymentService::item)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportedPayment> recentlyRejected() {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        readTimeout.apply();
        Instant since = Instant.now(clock).minus(java.time.Duration.ofDays(REJECTED_WINDOW_DAYS));
        return payments.findCreditorPortalRejectedSince(creditorId, since, LIST_LIMIT).stream()
                .map(CreditorPaymentService::item).toList();
    }

    // ------------------------------------------------------------------ apoio

    /** Informe já gravado com a mesma chave e o mesmo conteúdo (outro conteúdo → 409). */
    private Optional<UUID> replay(ReportCommand command, VerifiedEvidence evidence, String idempotencyKey) {
        if (idempotencyKey == null || evidence == null || !KEY.matcher(idempotencyKey).matches()) {
            return Optional.empty();
        }
        return idempotency.find(IDEMPOTENCY_SCOPE, scopedKey(idempotencyKey),
                requestHash(normalizedNumbers(command), command, evidence.sha256()));
    }

    private String scopedKey(String idempotencyKey) {
        return "cu:" + currentActor.get().id() + ":" + idempotencyKey;
    }

    /** Os mesmos números que {@code resolve} grava: sem espaços, sem repetição e em ordem. */
    private static String normalizedNumbers(ReportCommand c) {
        return String.join(",", c.titleNumbers().stream().map(n -> n == null ? "" : n.strip()).distinct().sorted().toList());
    }

    private UUID creditor(Permission permission) {
        Actor actor = currentActor.get();
        if (!actor.isCreditor()) {
            throw accessScope.denyActorType(RESOURCE);
        }
        accessScope.requirePermission(permission);
        return actor.creditorScope().creditorIds().iterator().next();
    }

    private void validate(ReportCommand c) {
        Guard.require(c != null && c.titleNumbers() != null && !c.titleNumbers().isEmpty(),
                "payment.creditor_titles_required", "Informe os títulos pagos");
        Guard.require(c.titleNumbers().size() <= MAX_TITLES, "payment.creditor_titles_too_many",
                "No máximo %d títulos por informe".formatted(MAX_TITLES));
        Guard.require(c.amount() != null && c.amount().signum() > 0 && c.amount().scale() <= 2,
                "payment.amount_invalid", "Valor do pagamento deve ser positivo");
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        Guard.require(c.paidOn() != null && !c.paidOn().isAfter(today), "payment.paid_at_future",
                "A data do pagamento não pode ser futura");
        Guard.require(!c.paidOn().isBefore(today.minusYears(5)), "payment.paid_at_too_old",
                "Data do pagamento muito antiga");
        Guard.require(c.method() != null && METHODS.contains(c.method()), "payment.method_invalid", "Meio de pagamento");
        Guard.require(c.reference() == null || c.reference().length() <= Payment.REFERENCE_MAX,
                "payment.reference_too_long", "Referência até 100 caracteres");
        Guard.require(c.note() == null || c.note().length() <= Payment.NOTE_MAX, "payment.note_too_long",
                "Observação até 1000 caracteres");
    }

    private record Resolved(UUID debtorId, Payment.Target target, String numbers, int titleCount) {
    }

    /**
     * Títulos do próprio credor pelos números exatos; todos do mesmo devedor e em aberto. Se estão num acordo ativo, o
     * destino é o acordo que cobre <b>todos</b> eles (parcelas em aberto, da mais antiga); mistura de títulos com e sem
     * acordo, ou de acordos diferentes, é recusada. Valor acima do saldo em aberto é recusado (decisão 1438).
     */
    private Resolved resolve(UUID creditorId, ReportCommand c) {
        Set<String> numbers = new LinkedHashSet<>();
        for (String raw : c.titleNumbers()) {
            String n = raw == null ? "" : raw.strip();
            Guard.require(NUMBER.matcher(n).matches(), "payment.creditor_title_invalid", "Número de título inválido");
            numbers.add(n);
        }
        List<UUID> ids = new ArrayList<>();
        for (String n : numbers) {
            TitleView t = titles.findByExternalId(creditorId, n).filter(v -> v.creditorId().equals(creditorId))
                    .orElseThrow(() -> notFound(n));
            ids.add(t.id());
        }
        List<TitleInfo> infos = resolver.titles(ids);
        UUID debtorId = infos.getFirst().debtorId();
        for (TitleInfo t : infos) {
            if (!t.openForPayment()) {
                throw notFound(t.externalId());
            }
            Guard.require(t.debtorId().equals(debtorId), "payment.creditor_titles_mixed",
                    "Os títulos informados são de devedores diferentes. Informe um pagamento por devedor.");
        }
        String joined = String.join(",", numbers.stream().sorted().toList());
        long underAgreement = infos.stream().filter(TitleInfo::underAgreement).count();
        Money amount = Money.of(c.amount());
        if (underAgreement == 0) {
            Money open = infos.stream().map(TitleInfo::openBalance).reduce(Money.ZERO, Money::plus);
            requireWithin(amount, open);
            return new Resolved(debtorId, new Payment.Target(TargetKind.TITLES, null, json.toJson(ids),
                    TitleStrategy.OLDEST_FIRST), joined, ids.size());
        }
        Guard.require(underAgreement == infos.size(), "payment.creditor_titles_mixed",
                "Parte dos títulos está num acordo e parte não. Informe os pagamentos separadamente.");
        List<AllocationAgreement> covering = agreements.active(creditorId, debtorId).stream()
                .filter(a -> a.titleIds().containsAll(ids)).toList();
        Guard.require(covering.size() == 1, "payment.creditor_titles_mixed",
                "Os títulos estão em acordos diferentes. Informe um pagamento por acordo.");
        AgreementTarget agreement = resolver.agreement(covering.getFirst().id());
        List<UUID> installments = agreement.installments().stream().filter(i -> i.remaining().isPositive())
                .sorted(Comparator.comparingInt(InstallmentInfo::number)).map(InstallmentInfo::id).toList();
        Guard.require(!installments.isEmpty(), "payment.creditor_agreement_paid",
                "As parcelas do acordo desses títulos já estão pagas ou em análise");
        requireWithin(amount, agreement.remaining());
        return new Resolved(debtorId, new Payment.Target(TargetKind.AGREEMENT, agreement.agreementId(),
                json.toJson(installments), null), joined, ids.size());
    }

    private static void requireWithin(Money amount, Money open) {
        Guard.require(!amount.isGreaterThan(open), "payment.creditor_amount_above_balance",
                "O valor informado é maior que o saldo em aberto desses títulos. Confira o valor ou os títulos.");
    }

    private static BusinessRuleException notFound(String number) {
        return new BusinessRuleException("payment.creditor_title_not_found",
                "Título %s não encontrado ou sem saldo em aberto para pagamento".formatted(number));
    }

    private static ConflictException limitReached() {
        return new ConflictException("payment.creditor_report_limit",
                "Há %d informes do seu time em análise na assessoria. Aguarde a análise para informar novos pagamentos."
                        .formatted(MAX_IN_REVIEW));
    }

    private static String requestHash(String numbers, ReportCommand c, String evidenceSha256) {
        String canonical = String.join("|", numbers, c.amount().stripTrailingZeros().toPlainString(),
                c.paidOn().toString(), c.method(), String.valueOf(c.reference()), String.valueOf(c.note()), evidenceSha256);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReportedPayment item(Payment p) {
        String status = switch (p.getStatus()) {
            case INFORMED -> "IN_REVIEW";
            case CONFIRMED, RECONCILED -> "CONFIRMED";
            case REJECTED -> "REJECTED";
            case REVERSED -> "REVERSED";
        };
        List<String> numbers = p.getCreditorTitleNumbers() == null ? List.of()
                : Arrays.stream(p.getCreditorTitleNumbers().split(",")).toList();
        String reference = p.getExternalReference() != null
                && p.getExternalReference().startsWith(Payment.ANONYMIZED_REFERENCE_PREFIX) ? null : p.getExternalReference();
        return new ReportedPayment(p.getId(), status, p.getAmount().amount(),
                LocalDate.ofInstant(p.getPaidAt(), TimeConfig.BUSINESS_ZONE), p.getMethod().name(), numbers,
                reference, p.getInformedAt(), p.getStatus() == PaymentStatus.INFORMED ? null : p.getReviewedAt(),
                p.getCreditorRejectionReason());
    }
}
