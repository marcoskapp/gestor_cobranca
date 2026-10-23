package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.port.out.CommitmentLock;
import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.FactKey;
import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitment;
import br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus;
import br.com.gestorcobranca.incentive.domain.model.commitment.CommitmentTally;
import br.com.gestorcobranca.incentive.domain.model.commitment.PromiseOutcome;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.CallbackSignal;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.Change;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.PromiseChange;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.PromiseSignal;
import br.com.gestorcobranca.shared.events.collection.CallbackStatusChanged;
import br.com.gestorcobranca.shared.events.collection.PromiseStatusChanged;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fatos de retornos e promessas (§2.1 "Retornos cumpridos", §2.2 "Promessas cumpridas"), a partir
 * de {@link CallbackStatusChanged} e {@link PromiseStatusChanged}. Executado pelos consumidores
 * dentro da transação de consumo idempotente ({@code shared.consumed_event}); eventos do mesmo
 * retorno ou promessa são serializados por lock e a situação é recalculada pelas regras de
 * {@link CommitmentPolicy}, de modo que reentregas e entregas fora de ordem dão o mesmo resultado.
 * <p>
 * Dimensões: retornos no dia do horário combinado (fuso de operação) e no responsável pelo
 * retorno; promessas na data prometida e em quem registrou (registro: no dia do registro). Faixa
 * de atraso e canal não são informados por esses eventos ({@code UNKNOWN}).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class CommitmentFactService {

    static final String CALLBACK = "callback";
    static final String PROMISE = "promise";

    private final FactStore facts;
    private final CommitmentLock lock;
    private final IncentiveMetrics metrics;

    CommitmentFactService(FactStore facts, CommitmentLock lock, IncentiveMetrics metrics) {
        this.facts = facts;
        this.lock = lock;
        this.metrics = metrics;
    }

    void callback(CallbackStatusChanged e) {
        metrics.factConsumed(CALLBACK);
        Optional<CallbackCommitmentStatus> status = CallbackCommitmentStatus.fromEvent(e.status());
        if (status.isEmpty() || e.taskId() == null || e.dueAt() == null) {
            metrics.factIgnored(CALLBACK, status.isEmpty() ? "unknown_status" : "incomplete");
            return;
        }
        lock.lockCallback(e.taskId());
        List<CallbackCommitment> known = facts.callbackCommitments(e.taskId());
        known.stream().filter(c -> c.dueAt().equals(e.dueAt()) && e.occurredAt().isBefore(c.lastEventAt()))
                .findFirst().ifPresent(c -> metrics.factOutOfOrder(CALLBACK));
        List<Change<CallbackCommitment>> changes = CommitmentPolicy.applyCallback(known, new CallbackSignal(e.taskId(),
                e.dueAt(), e.caseId(), e.creditorId(), e.portfolioId(), e.assigneeId(), status.get(), e.occurredAt()));
        Map<FactKey, CommitmentTally> deltas = new LinkedHashMap<>();
        for (Change<CallbackCommitment> change : changes) {
            if (change.before() != null) {
                add(deltas, callbackKey(change.before()), change.before().tally().negate());
            }
            add(deltas, callbackKey(change.after()), change.after().tally());
            facts.saveCallbackCommitment(change.after());
        }
        apply(deltas);
    }

    void promise(PromiseStatusChanged e) {
        metrics.factConsumed(PROMISE);
        if (e.promiseId() == null || e.status() == null) {
            metrics.factIgnored(PROMISE, "incomplete");
            return;
        }
        lock.lockPromise(e.promiseId());
        Optional<PromiseOutcome> known = facts.promiseOutcome(e.promiseId());
        if (known.isPresent() && e.occurredAt().isBefore(known.get().statusAt())) {
            metrics.factOutOfOrder(PROMISE);
        }
        PromiseSignal signal = new PromiseSignal(e.promiseId(), e.caseId(), e.creditorId(), e.portfolioId(),
                e.operatorId(), e.dueDate(), e.previousStatus(), e.status(), e.occurredAt());
        PromiseChange change = CommitmentPolicy.applyPromise(known.orElse(null), signal);
        Map<FactKey, CommitmentTally> deltas = new LinkedHashMap<>();
        if (change.registeredNow() && e.operatorId() != null) {
            add(deltas, new FactKey(FactIngestionService.day(e.occurredAt()), e.operatorId(), e.creditorId(),
                    e.portfolioId(), DelayBucket.UNKNOWN, null), CommitmentTally.promiseRegistered());
        }
        if (change.before() != null) {
            add(deltas, promiseKey(change.before()), change.before().tally().negate());
        }
        add(deltas, promiseKey(change.after()), change.after().tally());
        if (!change.after().equals(change.before())) {
            facts.savePromiseOutcome(change.after());
        }
        apply(deltas);
    }

    private void apply(Map<FactKey, CommitmentTally> deltas) {
        deltas.forEach((key, tally) -> {
            if (key != null && !tally.isZero()) {
                facts.incrementCommitments(key, tally);
            }
        });
    }

    private static void add(Map<FactKey, CommitmentTally> deltas, FactKey key, CommitmentTally tally) {
        if (key == null || tally.isZero()) {
            return;
        }
        deltas.merge(key, tally, CommitmentTally::plus);
    }

    /** Sem responsável humano o retorno não entra nos fatos de nenhum negociador. */
    private static FactKey callbackKey(CallbackCommitment c) {
        return c.assigneeId() == null ? null : new FactKey(FactIngestionService.day(c.dueAt()), c.assigneeId(),
                c.creditorId(), c.portfolioId(), DelayBucket.UNKNOWN, null);
    }

    private static FactKey promiseKey(PromiseOutcome o) {
        return o.operatorId() == null || o.dueDate() == null ? null
                : new FactKey(o.dueDate(), o.operatorId(), o.creditorId(), o.portfolioId(), DelayBucket.UNKNOWN, null);
    }
}
