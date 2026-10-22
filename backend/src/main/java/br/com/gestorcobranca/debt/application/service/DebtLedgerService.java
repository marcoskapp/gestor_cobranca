package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.service.TitleChangeNotifier.Trigger;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.LedgerComponent;
import br.com.gestorcobranca.debt.domain.model.LedgerEntryType;
import br.com.gestorcobranca.debt.domain.model.TitleStatus;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.service.TitleLedger;
import br.com.gestorcobranca.debt.domain.service.TitleLedger.Posting;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
@Transactional
class DebtLedgerService implements DebtLedgerPort {

    private static final int REFERENCE_TYPE_MAX = 40;

    private final DebtTitleRepository titles;
    private final LedgerRecorder recorder;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final TitleChangeNotifier notifier;

    DebtLedgerService(DebtTitleRepository titles, LedgerRecorder recorder, AccessScope accessScope,
                      AuditTrail auditTrail, TitleChangeNotifier notifier) {
        this.titles = titles;
        this.recorder = recorder;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.notifier = notifier;
    }

    @Override
    public LedgerResult applyPayment(PaymentApplication payment) {
        Guard.require(payment.paymentId() != null, "ledger.payment_required", "Pagamento obrigatório");
        return post(payment.titleId(), title -> TitleLedger.applyPayment(title, recorder.existingEntries(title),
                payment.paymentId(), parts(payment.parts()), payment.paidAt()), "debt_title.payment_applied", Trigger.PAYMENT);
    }

    @Override
    public LedgerResult reversePayment(PaymentReversal reversal) {
        Guard.require(reversal.paymentId() != null, "ledger.payment_required", "Pagamento obrigatório");
        return post(reversal.titleId(), title -> TitleLedger.reversePayment(title, recorder.existingEntries(title),
                reversal.paymentId(), reversal.reversedAt(), reversal.reason()), "debt_title.payment_reversed", Trigger.REVERSAL);
    }

    @Override
    public LedgerResult applyCharges(ChargeApplication charges) {
        Guard.require(charges.referenceType() != null && !charges.referenceType().isBlank()
                        && charges.referenceType().length() <= REFERENCE_TYPE_MAX,
                "ledger.reference_type_required", "Tipo de referência obrigatório");
        return post(charges.titleId(), title -> TitleLedger.applyCharges(title, recorder.existingEntries(title),
                charges.referenceType().trim(), charges.referenceId(), parts(charges.parts()), charges.occurredAt(),
                charges.reason()), "debt_title.charges_applied", Trigger.CHARGE);
    }

    @Override
    public LedgerResult applyDiscount(DiscountApplication discount) {
        Guard.require(discount.type() != null, "ledger.discount_type_invalid", "Tipo de concessão obrigatório");
        LedgerEntryType type = discount.type() == ConcessionType.DISCOUNT ? LedgerEntryType.DISCOUNT : LedgerEntryType.WRITE_OFF;
        return post(discount.titleId(), title -> TitleLedger.applyDiscount(title, recorder.existingEntries(title), type,
                discount.agreementId(), parts(discount.parts()), discount.occurredAt(), discount.reason()),
                "debt_title.discount_applied", Trigger.DISCOUNT);
    }

    @Override
    public void markDisputed(Collection<UUID> titleIds, UUID disputeId, boolean blocksCollection) {
        Guard.require(disputeId != null, "title.dispute_required", "Contestação obrigatória");
        for (DebtTitle title : lockAll(titleIds)) {
            UUID previousDispute = title.getDisputeId();
            boolean previousBlocked = title.isDisputeBlocked();
            if (title.markDisputed(disputeId, blocksCollection)) {
                titles.save(title);
                auditTrail.record(AuditEntry.of("debt_title.dispute_marked", "debt_title", title.getId())
                        .creditor(title.getCreditorId())
                        .change("disputeId", previousDispute, disputeId)
                        .change("disputeBlocked", previousBlocked, blocksCollection));
                notifier.changedAlways(title, title.getStatus(), title.getOpenBalance(), Trigger.DISPUTE);
            }
        }
    }

    @Override
    public void clearDispute(Collection<UUID> titleIds, UUID disputeId) {
        Guard.require(disputeId != null, "title.dispute_required", "Contestação obrigatória");
        for (DebtTitle title : lockAll(titleIds)) {
            boolean previousBlocked = title.isDisputeBlocked();
            if (title.clearDispute(disputeId)) {
                titles.save(title);
                auditTrail.record(AuditEntry.of("debt_title.dispute_cleared", "debt_title", title.getId())
                        .creditor(title.getCreditorId())
                        .change("disputeId", disputeId, null)
                        .change("disputeBlocked", previousBlocked, false));
                notifier.changedAlways(title, title.getStatus(), title.getOpenBalance(), Trigger.DISPUTE);
            }
        }
    }

    private LedgerResult post(UUID titleId, Function<DebtTitle, Posting> operation, String auditAction, Trigger trigger) {
        DebtTitle title = lock(titleId);
        TitleStatus statusBefore = title.getStatus();
        Posting posting = operation.apply(title);
        recorder.record(title, posting, auditAction, trigger);
        return new LedgerResult(title.getId(), posting.applied(),
                posting.applied() ? posting.previousStatus().name() : statusBefore.name(), title.getStatus().name(),
                title.getOpenBalance().amount(), title.getPaidTotal().amount());
    }

    private DebtTitle lock(UUID titleId) {
        Guard.require(titleId != null, "title.required", "Título obrigatório");
        DebtTitle title = titles.findByIdForUpdate(titleId).orElseThrow(() -> ResourceNotFoundException.of("debt_title", titleId));
        accessScope.requireCreditor(title.getCreditorId());
        return title;
    }

    private List<DebtTitle> lockAll(Collection<UUID> titleIds) {
        Guard.require(titleIds != null && !titleIds.isEmpty(), "title.required", "Informe ao menos um título");
        Set<UUID> ids = new LinkedHashSet<>(titleIds);
        List<DebtTitle> locked = titles.findAllByIdInForUpdate(ids);
        if (locked.size() != ids.size()) {
            UUID missing = ids.stream().filter(id -> locked.stream().noneMatch(t -> t.getId().equals(id))).findFirst()
                    .orElseThrow();
            throw ResourceNotFoundException.of("debt_title", missing);
        }
        locked.forEach(t -> accessScope.requireCreditor(t.getCreditorId()));
        return locked;
    }

    static Map<LedgerComponent, Money> parts(ComponentAmounts amounts) {
        Guard.require(amounts != null, "ledger.parts_required", "Informe ao menos um componente com valor");
        Map<LedgerComponent, Money> parts = new EnumMap<>(LedgerComponent.class);
        put(parts, LedgerComponent.PRINCIPAL, amounts.principal());
        put(parts, LedgerComponent.INTEREST, amounts.interest());
        put(parts, LedgerComponent.FINE, amounts.fine());
        put(parts, LedgerComponent.FEES, amounts.fees());
        put(parts, LedgerComponent.HONORARIUM, amounts.honorarium());
        return parts;
    }

    private static void put(Map<LedgerComponent, Money> parts, LedgerComponent component, BigDecimal amount) {
        if (amount != null && amount.signum() != 0) {
            parts.put(component, Money.of(amount));
        }
    }
}
