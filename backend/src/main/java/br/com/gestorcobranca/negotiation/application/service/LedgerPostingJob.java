package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.BucketPosting;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementPayment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.LedgerPart;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Lança no razão do título ({@code DebtLedgerPort}) o que o acordo já decidiu, em transação própria
 * e com o acordo bloqueado:
 * <ol>
 *   <li>pagamentos aplicados pendentes, por título: encargo reconhecido ({@code applyCharges}, referência
 *       {@code agreement_payment}/pagamento); se o título ficou integralmente pago, os encargos brutos ainda
 *       não reconhecidos ({@code applyCharges}, referência {@code agreement}); o pagamento por componente
 *       ({@code applyPayment}); e o desconto concedido ({@code applyDiscount}), que quita o título;</li>
 *   <li>estornos de pagamentos já lançados: {@code reversePayment} (espelho no razão).</li>
 * </ol>
 * Todos os lançamentos são idempotentes no razão (referência), então reexecutar o job não duplica.
 * Recusa do razão (regra de negócio) é permanente: o job vai para DEAD com alarme
 * ({@code negotiation.ledger.postings{outcome=FAILED}}) e a baixa da parcela continua registrada.
 */
@Component
class LedgerPostingJob implements JobHandler<LedgerPostingJob.Payload> {

    static final String TYPE = "negotiation.ledger_posting";
    private static final Logger LOG = LoggerFactory.getLogger(LedgerPostingJob.class);

    record Payload(UUID agreementId) {
    }

    static void enqueue(JobQueue jobs, UUID agreementId) {
        jobs.enqueue(TYPE, new Payload(agreementId), null, agreementId.toString());
    }

    private final AgreementRepository agreements;
    private final DebtLedgerPort ledger;
    private final NegotiationMetrics metrics;
    private final JsonCodec json;
    private final Clock clock;

    LedgerPostingJob(AgreementRepository agreements, DebtLedgerPort ledger, NegotiationMetrics metrics, JsonCodec json,
                     Clock clock) {
        this.agreements = agreements;
        this.ledger = ledger;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public boolean isPermanent(RuntimeException e) {
        return e instanceof DomainException || JobHandler.super.isPermanent(e);
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Agreement a = agreements.findByIdForUpdate(payload.agreementId()).orElse(null);
        if (a == null) {
            return;
        }
        String operation = "PAYMENT";
        try {
            for (AgreementPayment p : agreements.findPayments(a.getId())) {
                if (p.getLedgerStatus() == AgreementPayment.LedgerStatus.PENDING) {
                    operation = "PAYMENT";
                    postPayment(a, p);
                    p.ledgerPosted(Instant.now(clock));
                    agreements.save(p);
                    metrics.ledgerPosting(operation, "POSTED");
                } else if (p.getStatus() == AgreementPayment.Status.REVERSED
                        && p.getLedgerStatus() == AgreementPayment.LedgerStatus.POSTED) {
                    operation = "REVERSAL";
                    postReversal(p);
                    p.ledgerReversed();
                    agreements.save(p);
                    metrics.ledgerPosting(operation, "POSTED");
                }
            }
        } catch (RuntimeException e) {
            metrics.ledgerPosting(operation, "FAILED");
            LOG.atError().addKeyValue("agreementId", a.getId()).addKeyValue("operation", operation).setCause(e)
                    .log("Falha ao lançar o acordo no razão do título");
            throw e;
        }
    }

    private void postPayment(Agreement a, AgreementPayment p) {
        Map<UUID, List<BucketPosting>> byTitle = group(LedgerLines.postings(json, p.getLedgerPostings()), BucketPosting::titleId);
        byTitle.forEach((titleId, lines) -> {
            ComponentAmounts charges = amounts(lines, BucketPosting::part, BucketPosting::charge);
            if (charges != null) {
                ledger.applyCharges(new DebtLedgerPort.ChargeApplication(titleId, "agreement_payment", p.getPaymentId(),
                        charges, p.getPaidAt(), "Encargos reconhecidos pelo acordo " + a.getId()));
            }
            // título integralmente pago: encargo bruto restante antes do pagamento (o saldo não zera antes do desconto)
            ComponentAmounts settlementCharges = amounts(lines, BucketPosting::part, BucketPosting::settlementCharge);
            if (settlementCharges != null) {
                ledger.applyCharges(new DebtLedgerPort.ChargeApplication(titleId, "agreement", a.getId(),
                        settlementCharges, p.getPaidAt(), "Encargos do acordo quitado " + a.getId()));
            }
            ComponentAmounts payments = amounts(lines, BucketPosting::part, BucketPosting::payment);
            if (payments != null) {
                ledger.applyPayment(new DebtLedgerPort.PaymentApplication(titleId, p.getPaymentId(), payments, p.getPaidAt()));
            }
            ComponentAmounts discounts = amounts(lines, BucketPosting::part, BucketPosting::discount);
            if (discounts != null) {
                ledger.applyDiscount(new DebtLedgerPort.DiscountApplication(titleId, a.getId(),
                        DebtLedgerPort.ConcessionType.DISCOUNT, discounts, p.getPaidAt(),
                        "Desconto concedido no acordo " + a.getId()));
            }
        });
    }

    private void postReversal(AgreementPayment p) {
        String reason = p.getReversalReason() == null ? "Estorno do pagamento do acordo" : p.getReversalReason();
        Instant at = p.getReversedAt() == null ? Instant.now(clock) : p.getReversedAt();
        group(LedgerLines.postings(json, p.getLedgerPostings()), BucketPosting::titleId).forEach((titleId, lines) -> {
            if (lines.stream().anyMatch(l -> l.payment().isPositive())) {
                ledger.reversePayment(new DebtLedgerPort.PaymentReversal(titleId, p.getPaymentId(), at, reason));
            }
        });
    }

    private static <T> Map<UUID, List<T>> group(List<T> items, Function<T, UUID> key) {
        Map<UUID, List<T>> grouped = new LinkedHashMap<>();
        items.forEach(i -> grouped.computeIfAbsent(key.apply(i), k -> new java.util.ArrayList<>()).add(i));
        return grouped;
    }

    /** Valores positivos por componente (nulo se nenhum). */
    private static <T> ComponentAmounts amounts(List<T> lines, Function<T, LedgerPart> part, Function<T, Money> value) {
        Map<LedgerPart, Money> sum = new EnumMap<>(LedgerPart.class);
        lines.forEach(l -> {
            Money v = value.apply(l);
            if (v.isPositive()) {
                sum.merge(part.apply(l), v, Money::plus);
            }
        });
        if (sum.isEmpty()) {
            return null;
        }
        return new ComponentAmounts(get(sum, LedgerPart.PRINCIPAL), get(sum, LedgerPart.INTEREST), get(sum, LedgerPart.FINE),
                get(sum, LedgerPart.FEES), get(sum, LedgerPart.HONORARIUM));
    }

    private static java.math.BigDecimal get(Map<LedgerPart, Money> sum, LedgerPart part) {
        Money m = sum.get(part);
        return m == null ? null : m.amount();
    }
}
