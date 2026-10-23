package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.StatementParser.ParsedLine;
import br.com.gestorcobranca.payment.domain.model.DivergenceSource;
import br.com.gestorcobranca.payment.domain.model.DivergenceType;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PaymentMethod;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.model.ReconciliationMode;
import br.com.gestorcobranca.payment.domain.model.ReviewMode;
import br.com.gestorcobranca.payment.domain.model.StatementImport;
import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import br.com.gestorcobranca.payment.domain.model.StatementLineStatus;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.BankStatementRepository;
import br.com.gestorcobranca.payment.domain.service.PixIdentifiers;
import br.com.gestorcobranca.payment.domain.service.StatementFingerprint;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Conciliação automática de uma linha de extrato/arquivo de retorno (F15), em ordem:
 * <ol>
 *   <li>débito → ignorado; mesma transação já importada (impressão digital) → ignorada, sem nova baixa;</li>
 *   <li>identificador da transação = {@code endToEndId}/identificador do provedor ou referência externa
 *       de pagamento informado;</li>
 *   <li>referência = {@code txid} ou id de instrução (sem pagamento ainda: o arquivo de retorno é a
 *       confirmação — cria o pagamento integrado e aloca ao destino da instrução), id de pagamento ou id
 *       de parcela (pagamento confirmado da parcela com o mesmo valor e data dentro da tolerância);</li>
 *   <li>sem correspondência → divergência {@code UNMATCHED}.</li>
 * </ol>
 * Valor diferente → {@code AMOUNT_MISMATCH}; pagamento ainda não confirmado → {@code NOT_CONFIRMED}.
 * Importação restrita a um credor ignora correspondências de outros credores.
 */
@Component
class StatementMatcher {

    private final BankStatementRepository statements;
    private final PaymentRepository payments;
    private final PaymentInstructionRepository instructions;
    private final PaymentAllocationRepository allocations;
    private final PaymentConfirmation confirmation;
    private final PaymentRecorder recorder;
    private final Divergences divergences;
    private final PaymentSettingsProvider settings;
    private final PaymentMetrics metrics;
    private final Clock clock;

    StatementMatcher(BankStatementRepository statements, PaymentRepository payments, PaymentInstructionRepository instructions,
                     PaymentAllocationRepository allocations, PaymentConfirmation confirmation, PaymentRecorder recorder,
                     Divergences divergences, PaymentSettingsProvider settings, PaymentMetrics metrics, Clock clock) {
        this.statements = statements;
        this.payments = payments;
        this.instructions = instructions;
        this.allocations = allocations;
        this.confirmation = confirmation;
        this.recorder = recorder;
        this.divergences = divergences;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @param occurrences contagem de linhas idênticas sem identificador no arquivo (para a impressão digital) */
    StatementLineStatus process(StatementImport source, ParsedLine parsed, Map<String, Integer> occurrences) {
        Money amount = Money.of(parsed.amount());
        String txId = blankToNull(parsed.transactionId());
        String reference = blankToNull(parsed.reference());
        String key = parsed.paidOn() + "|" + amount + "|" + parsed.credit() + "|" + reference;
        int occurrence = txId == null ? occurrences.merge(key, 1, Integer::sum) : 1;
        String fingerprint = StatementFingerprint.of(parsed.paidOn(), amount, parsed.credit(), txId, reference, occurrence);
        StatementLineStatus initial = !parsed.credit() ? StatementLineStatus.IGNORED_DEBIT
                : statements.existsLineByFingerprint(fingerprint) ? StatementLineStatus.IGNORED_DUPLICATE
                : StatementLineStatus.DIVERGENT;
        BankStatementLine line = statements.save(new BankStatementLine(source.getId(), parsed.lineNumber(), parsed.paidOn(), amount,
                parsed.credit(), txId, reference, parsed.description(), fingerprint, initial, null));
        if (initial != StatementLineStatus.DIVERGENT) {
            metrics.statementLine(initial.name());
            return initial;
        }
        Outcome outcome = match(source, line, txId, reference);
        line.outcome(outcome.status(), outcome.paymentId());
        statements.save(line);
        metrics.statementLine(outcome.status().name());
        return outcome.status();
    }

    private record Outcome(StatementLineStatus status, UUID paymentId) {
    }

    private Outcome match(StatementImport source, BankStatementLine line, String txId, String reference) {
        if (txId != null) {
            Optional<Payment> byTransaction = payments.findByProviderTransactionIdForUpdate(txId)
                    .filter(p -> inScope(source, p));
            if (byTransaction.isEmpty()) {
                List<Payment> external = payments.findByExternalReferenceAndStatus(txId, PaymentStatus.CONFIRMED).stream()
                        .filter(p -> inScope(source, p)).toList();
                byTransaction = external.size() == 1 ? Optional.of(external.getFirst()) : Optional.empty();
            }
            if (byTransaction.isPresent()) {
                return againstPayment(source, line, byTransaction.get(), txId);
            }
        }
        if (reference != null) {
            Optional<PaymentInstruction> instruction = PixIdentifiers.validTxid(reference)
                    ? instructions.findByTxidForUpdate(reference) : Optional.empty();
            UUID id = uuid(reference);
            if (instruction.isEmpty() && id != null) {
                instruction = instructions.findByIdForUpdate(id);
            }
            instruction = instruction.filter(i -> source.getCreditorId() == null || source.getCreditorId().equals(i.getCreditorId()));
            if (instruction.isPresent()) {
                return againstInstruction(source, line, instruction.get(), txId);
            }
            if (id != null) {
                Optional<Payment> payment = payments.findByIdForUpdate(id).filter(p -> inScope(source, p));
                if (payment.isPresent()) {
                    return againstPayment(source, line, payment.get(), txId);
                }
                Optional<Payment> byInstallment = byInstallment(source, line, id);
                if (byInstallment.isPresent()) {
                    return againstPayment(source, line, byInstallment.get(), txId);
                }
            }
        }
        divergences.open(DivergenceType.UNMATCHED, DivergenceSource.STATEMENT, source.getCreditorId(), null, null,
                line.getId(), null, line.getAmount(), null, line.getPaidOn());
        return new Outcome(StatementLineStatus.DIVERGENT, null);
    }

    /** @param found pagamento já bloqueado pela consulta de origem, ou carregado sem alteração na transação */
    private Outcome againstPayment(StatementImport source, BankStatementLine line, Payment found, String txId) {
        Payment p = found;
        if (!p.isEffective()) {
            divergence(DivergenceType.NOT_CONFIRMED, source, line, p, null);
            return new Outcome(StatementLineStatus.DIVERGENT, p.getId());
        }
        if (!p.getAmount().equals(line.getAmount())) {
            divergence(DivergenceType.AMOUNT_MISMATCH, source, line, p, p.getAmount());
            return new Outcome(StatementLineStatus.DIVERGENT, p.getId());
        }
        if (p.getStatus() == PaymentStatus.RECONCILED) {
            return new Outcome(StatementLineStatus.ALREADY_RECONCILED, p.getId());
        }
        if (txId != null && p.getProviderTransactionId() == null && p.getExternalReference() == null) {
            p.attachProviderTransaction(txId);
        }
        reconcile(p, line, source);
        return new Outcome(StatementLineStatus.RECONCILED, p.getId());
    }

    private Outcome againstInstruction(StatementImport source, BankStatementLine line, PaymentInstruction instruction,
                                       String txId) {
        List<Payment> existing = payments.findByInstructionIdOrderByCreatedAtAsc(instruction.getId()).stream()
                .filter(Payment::isEffective).toList();
        if (!existing.isEmpty()) {
            Optional<Payment> same = existing.stream().filter(p -> p.getAmount().equals(line.getAmount())
                    && (txId == null || p.getProviderTransactionId() == null || txId.equals(p.getProviderTransactionId())))
                    .findFirst();
            return same.map(p -> againstPayment(source, line, p, txId))
                    .orElseGet(() -> {
                        divergence(DivergenceType.AMOUNT_MISMATCH, source, line, existing.getFirst(),
                                existing.getFirst().getAmount());
                        return new Outcome(StatementLineStatus.DIVERGENT, existing.getFirst().getId());
                    });
        }
        Instant now = Instant.now(clock);
        Instant paidAt = line.getPaidOn().atTime(LocalTime.NOON).atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        Payment payment = payments.saveAndFlush(Payment.fromProvider(method(instruction), instruction.getCreditorId(),
                instruction.getDebtorId(), line.getAmount(), paidAt.isAfter(now) ? now : paidAt, instruction.getId(), txId,
                ReviewMode.STATEMENT, now));
        confirmation.confirmed(payment, instruction, "statement");
        reconcile(payment, line, source);
        if (!line.getAmount().equals(instruction.getAmount())) {
            divergence(DivergenceType.AMOUNT_MISMATCH, source, line, payment, instruction.getAmount());
        }
        return new Outcome(StatementLineStatus.CREATED_PAYMENT, payment.getId());
    }

    private Optional<Payment> byInstallment(StatementImport source, BankStatementLine line, UUID installmentId) {
        int tolerance = settings.current().reconciliationDateToleranceDays();
        List<UUID> paymentIds = allocations.findActiveByInstallment(installmentId).stream()
                .map(PaymentAllocation::getPaymentId).distinct().toList();
        List<Payment> candidates = payments.findAllByIdIn(paymentIds).stream()
                .filter(p -> inScope(source, p) && p.getStatus() == PaymentStatus.CONFIRMED
                        && p.getAmount().equals(line.getAmount())
                        && Math.abs(ChronoUnit.DAYS.between(p.getPaidAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(),
                        line.getPaidOn())) <= tolerance)
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private void reconcile(Payment p, BankStatementLine line, StatementImport source) {
        if (p.reconcile(ReconciliationMode.STATEMENT, source.getRequestedBy(), line.getId(), Instant.now(clock))) {
            payments.save(p);
            recorder.reconciled(p, ReconciliationMode.STATEMENT, source.getId());
        }
    }

    private void divergence(DivergenceType type, StatementImport source, BankStatementLine line, Payment p, Money expected) {
        divergences.open(type, DivergenceSource.STATEMENT, p == null ? source.getCreditorId() : p.getCreditorId(),
                p == null ? null : p.getId(), p == null ? null : p.getInstructionId(), line.getId(), null, line.getAmount(),
                expected, line.getPaidOn());
    }

    private static boolean inScope(StatementImport source, Payment p) {
        return source.getCreditorId() == null || Objects.equals(source.getCreditorId(), p.getCreditorId());
    }

    private static PaymentMethod method(PaymentInstruction instruction) {
        return switch (instruction.getType()) {
            case PIX_IMMEDIATE, PIX_DUE -> PaymentMethod.PIX;
            case BOLETO -> PaymentMethod.BOLETO;
            case LINK -> PaymentMethod.CARD;
        };
    }

    private static UUID uuid(String value) {
        try {
            return value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
