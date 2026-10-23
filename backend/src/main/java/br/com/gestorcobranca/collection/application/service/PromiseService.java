package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.PaymentPromise;
import br.com.gestorcobranca.collection.domain.model.PromiseStatus;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.PaymentPromiseRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Promessas de pagamento: consulta, cancelamento e apuração (cumprimento por pagamento
 * confirmado, reabertura por estorno, quebra por vencimento).
 * <p>
 * Vínculo pagamento → promessa: pagamento confirmado <b>sem acordo</b> ({@code agreementId} nulo —
 * pagamento de acordo pertence ao acordo, não à promessa simples) do mesmo devedor e credor, na
 * carteira do caso da promessa (a do evento ou, sem ela, a dos títulos alocados), feito a partir
 * do registro da promessa.
 */
@Service
@Transactional
class PromiseService implements PromiseUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PromiseService.class);
    private static final Set<PromiseStatus> OPEN_FOR_PAYMENTS = EnumSet.of(PromiseStatus.PENDING, PromiseStatus.KEPT);

    private final PaymentPromiseRepository promises;
    private final CollectionCaseRepository cases;
    private final CaseAccess access;
    private final PromiseRecorder recorder;
    private final CaseRecorder caseRecorder;
    private final CaseAgenda agenda;
    private final DebtTitleUseCases titles;
    private final AccessScope accessScope;
    private final Clock clock;

    PromiseService(PaymentPromiseRepository promises, CollectionCaseRepository cases, CaseAccess access,
                   PromiseRecorder recorder, CaseRecorder caseRecorder, CaseAgenda agenda, DebtTitleUseCases titles,
                   AccessScope accessScope, Clock clock) {
        this.promises = promises;
        this.cases = cases;
        this.access = access;
        this.recorder = recorder;
        this.caseRecorder = caseRecorder;
        this.agenda = agenda;
        this.titles = titles;
        this.accessScope = accessScope;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromiseView> listByCase(UUID caseId) {
        CollectionCase c = access.visible(caseId);
        return promises.findByCaseIdOrderByPromisedAtDesc(c.getId()).stream().map(CollectionViews::promise).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PromiseView get(UUID promiseId) {
        return CollectionViews.promise(load(promiseId));
    }

    @Override
    public PromiseView cancel(UUID promiseId, String reason) {
        PaymentPromise promise = load(promiseId);
        accessScope.requireCreditor(promise.getCreditorId());
        access.lockDebtor(promise.getDebtorId());
        CollectionCase c = cases.findById(promise.getCaseId()).orElseThrow();
        if (c.getStatus().isActive()) {
            access.requireWorkable(c);
        }
        PromiseStatus previous = promise.getStatus();
        promise.cancel(reason, Instant.now(clock));
        PaymentPromise saved = recorder.changed(promise, previous, reason);
        leavePromiseStage(c, "Promessa cancelada", "promise_cancelled");
        return CollectionViews.promise(saved);
    }

    /** Substitui a promessa pendente do caso (nova promessa ou acordo formalizado). */
    @Transactional(propagation = Propagation.MANDATORY)
    void cancelPending(UUID caseId, String reason) {
        Instant now = Instant.now(clock);
        for (PaymentPromise pending : promises.findByCaseIdAndStatus(caseId, PromiseStatus.PENDING)) {
            pending.cancel(reason, now);
            recorder.changed(pending, PromiseStatus.PENDING, reason);
        }
    }

    // ------------------------------------------------------------------ apuração (eventos e job)

    @Transactional(propagation = Propagation.MANDATORY)
    void paymentConfirmed(PaymentConfirmed e) {
        if (e.agreementId() != null || e.amount() == null || e.amount().signum() <= 0) {
            return;
        }
        access.lockDebtor(e.debtorId());
        List<PaymentPromise> candidates = promises.findByDebtorIdAndCreditorIdAndStatusIn(e.debtorId(), e.creditorId(),
                OPEN_FOR_PAYMENTS);
        if (candidates.isEmpty()) {
            return;
        }
        Set<UUID> portfolios = e.portfolioId() != null ? Set.of(e.portfolioId())
                : e.allocations().stream().map(a -> titles.get(a.titleId()).portfolioId()).collect(Collectors.toSet());
        Instant now = Instant.now(clock);
        Instant paidAt = e.paidAt() == null ? e.occurredAt() : e.paidAt();
        for (PaymentPromise promise : candidates) {
            if (!portfolios.contains(promise.getPortfolioId())) {
                continue;
            }
            PromiseStatus previous = promise.getStatus();
            boolean kept = promise.registerPayment(e.paymentId(), Money.of(e.amount()), paidAt, now);
            recorder.changed(promise, previous, "Pagamento confirmado " + e.paymentId());
            if (kept) {
                cases.findById(promise.getCaseId()).ifPresent(c -> leavePromiseStage(c, "Promessa cumprida", "promise_kept"));
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void paymentReversed(PaymentReversed e) {
        if (e.debtorId() != null) {
            access.lockDebtor(e.debtorId());
        }
        for (PaymentPromise promise : promises.findByPaymentId(e.paymentId())) {
            PromiseStatus previous = promise.getStatus();
            boolean reopened = promise.reversePayment(e.paymentId());
            recorder.changed(promise, previous, "Pagamento estornado " + e.paymentId());
            if (reopened) {
                cases.findById(promise.getCaseId()).filter(c -> c.getStatus().isActive()).ifPresent(c -> {
                    CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
                    c.moveToStage(CaseStatus.PROMISE, "Pagamento da promessa estornado");
                    agenda.refresh(c);
                    caseRecorder.commit(c, before, AssignmentMode.ROUTING, null, "promise_reopened");
                });
            }
        }
    }

    /**
     * Quebra as promessas pendentes cujo prazo de tolerância terminou antes de hoje.
     *
     * @return quantidade quebrada neste lote
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int breakOverdue(int limit) {
        LocalDate today = CollectionViews.today(clock);
        Instant now = Instant.now(clock);
        int broken = 0;
        for (PaymentPromise promise : promises.findPendingBrokenBy(today, limit)) {
            access.lockDebtor(promise.getDebtorId());
            if (promise.breakIfOverdue(today, now)) {
                recorder.changed(promise, PromiseStatus.PENDING, promise.getResolutionReason());
                cases.findById(promise.getCaseId()).ifPresent(c -> leavePromiseStage(c, "Promessa quebrada", "promise_broken"));
                broken++;
            }
        }
        if (broken > 0) {
            LOG.atInfo().addKeyValue("broken", broken).log("Promessas de pagamento quebradas por vencimento");
        }
        return broken;
    }

    /** Sai do estágio PROMISE (para IN_NEGOTIATION) quando a promessa é resolvida, e recalcula a próxima ação. */
    private void leavePromiseStage(CollectionCase c, String reason, String trigger) {
        if (!c.getStatus().isActive()) {
            return;
        }
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        boolean inPromise = c.getStatus() == CaseStatus.PROMISE
                || (c.getStatus().isHold() && Objects.equals(c.getResumeStatus(), CaseStatus.PROMISE));
        if (inPromise && promises.findByCaseIdAndStatus(c.getId(), PromiseStatus.PENDING).isEmpty()) {
            c.moveToStage(CaseStatus.IN_NEGOTIATION, reason);
        }
        agenda.refresh(c);
        caseRecorder.commit(c, before, AssignmentMode.ROUTING, null, trigger);
    }

    private PaymentPromise load(UUID promiseId) {
        PaymentPromise promise = promises.findById(promiseId)
                .orElseThrow(() -> ResourceNotFoundException.of("promise", promiseId));
        accessScope.requireVisible("promise", promiseId, promise.getCreditorId());
        return promise;
    }
}
