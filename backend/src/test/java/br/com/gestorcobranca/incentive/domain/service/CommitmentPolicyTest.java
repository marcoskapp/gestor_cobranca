package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitment;
import br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus;
import br.com.gestorcobranca.incentive.domain.model.commitment.CommitmentTally;
import br.com.gestorcobranca.incentive.domain.model.commitment.PromiseOutcome;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.CallbackSignal;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.Change;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.PromiseChange;
import br.com.gestorcobranca.incentive.domain.service.CommitmentPolicy.PromiseSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus.CANCELLED;
import static br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus.COMPLETED_LATE;
import static br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus.KEPT;
import static br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus.MISSED;
import static br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus.SCHEDULED;
import static br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus.SUPERSEDED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Situações de retornos e promessas: o resultado (soma das contribuições) não depende da ordem de
 * entrega dos eventos — cada cenário é aplicado em todas as permutações.
 */
class CommitmentPolicyTest {

    private static final UUID TASK = UUID.randomUUID();
    private static final UUID X = UUID.randomUUID();
    private static final UUID Y = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant DUE_1 = Instant.parse("2026-10-05T15:00:00Z");
    private static final Instant DUE_2 = Instant.parse("2026-10-06T15:00:00Z");

    private static CallbackSignal callback(Instant due, UUID assignee, CallbackCommitmentStatus status, int minute) {
        return new CallbackSignal(TASK, due, null, null, null, assignee, status, T0.plusSeconds(60L * minute));
    }

    /** Aplica os sinais como o serviço: a cada mudança, a contribuição nova menos a anterior por (responsável, horário). */
    private static Result runCallbacks(List<CallbackSignal> signals) {
        Map<Instant, CallbackCommitment> store = new HashMap<>();
        Map<String, CommitmentTally> facts = new HashMap<>();
        for (CallbackSignal s : signals) {
            for (Change<CallbackCommitment> change : CommitmentPolicy.applyCallback(store.values(), s)) {
                if (change.before() != null) {
                    facts.merge(key(change.before()), change.before().tally().negate(), CommitmentTally::plus);
                }
                facts.merge(key(change.after()), change.after().tally(), CommitmentTally::plus);
                store.put(change.after().dueAt(), change.after());
            }
        }
        facts.values().removeIf(CommitmentTally::isZero);
        Map<Instant, CallbackCommitmentStatus> statuses = new HashMap<>();
        store.forEach((due, c) -> statuses.put(due, c.status()));
        return new Result(facts, statuses);
    }

    private record Result(Map<String, CommitmentTally> facts, Map<Instant, CallbackCommitmentStatus> statuses) {
    }

    private static String key(CallbackCommitment c) {
        return c.assigneeId() + "@" + c.dueAt();
    }

    private static Result assertOrderIndependent(List<CallbackSignal> signals) {
        Result expected = runCallbacks(signals);
        for (List<CallbackSignal> order : permutations(signals)) {
            Result actual = runCallbacks(order);
            assertThat(actual.facts()).as("ordem %s", order).isEqualTo(expected.facts());
            assertThat(actual.statuses()).as("ordem %s", order).isEqualTo(expected.statuses());
        }
        return expected;
    }

    @Test
    void keptCallbackCountsAsScheduledAndKeptInAnyOrder() {
        Result r = assertOrderIndependent(List.of(callback(DUE_1, X, SCHEDULED, 0), callback(DUE_1, X, KEPT, 5)));
        assertThat(r.facts()).containsExactly(Map.entry(X + "@" + DUE_1, CommitmentTally.callback(true, true, false)));
    }

    @Test
    void missedThenCompletedLateOrCancelledStaysLate() {
        Result late = assertOrderIndependent(List.of(callback(DUE_1, X, SCHEDULED, 0), callback(DUE_1, X, MISSED, 200),
                callback(DUE_1, X, COMPLETED_LATE, 300)));
        assertThat(late.facts()).containsExactly(Map.entry(X + "@" + DUE_1, CommitmentTally.callback(true, false, true)));
        assertThat(late.statuses()).containsEntry(DUE_1, COMPLETED_LATE);

        // cancelar um retorno já atrasado (ex.: caso encerrado) não apaga o atraso
        Result cancelled = assertOrderIndependent(List.of(callback(DUE_1, X, SCHEDULED, 0),
                callback(DUE_1, X, MISSED, 200), callback(DUE_1, X, CANCELLED, 300)));
        assertThat(cancelled.statuses()).containsEntry(DUE_1, MISSED);
        assertThat(cancelled.facts()).containsExactly(Map.entry(X + "@" + DUE_1, CommitmentTally.callback(true, false, true)));
    }

    @Test
    void cancelledBeforeDueLeavesTheDenominator() {
        Result r = assertOrderIndependent(List.of(callback(DUE_1, X, SCHEDULED, 0), callback(DUE_1, X, CANCELLED, 10)));
        assertThat(r.facts()).isEmpty();
        assertThat(r.statuses()).containsEntry(DUE_1, CANCELLED);
    }

    @Test
    void reschedulingBeforeDueSupersedesThePreviousCommitment() {
        Result r = assertOrderIndependent(List.of(callback(DUE_1, X, SCHEDULED, 0), callback(DUE_2, X, SCHEDULED, 30),
                callback(DUE_2, X, KEPT, 90)));
        assertThat(r.statuses()).containsEntry(DUE_1, SUPERSEDED).containsEntry(DUE_2, KEPT);
        assertThat(r.facts()).containsExactly(Map.entry(X + "@" + DUE_2, CommitmentTally.callback(true, true, false)));
    }

    @Test
    void reschedulingAfterMissKeepsTheMissAndOpensANewCommitment() {
        Result r = assertOrderIndependent(List.of(callback(DUE_1, X, SCHEDULED, 0), callback(DUE_1, X, MISSED, 200),
                callback(DUE_2, X, SCHEDULED, 210)));
        assertThat(r.statuses()).containsEntry(DUE_1, MISSED).containsEntry(DUE_2, SCHEDULED);
        assertThat(r.facts()).containsEntry(X + "@" + DUE_1, CommitmentTally.callback(true, false, true))
                .containsEntry(X + "@" + DUE_2, CommitmentTally.callback(true, false, false));
    }

    @Test
    void reassignedCallbackMovesToTheResponsibleOfTheLatestEvent() {
        Result r = assertOrderIndependent(List.of(callback(DUE_1, X, SCHEDULED, 0), callback(DUE_1, Y, KEPT, 30)));
        assertThat(r.facts()).containsExactly(Map.entry(Y + "@" + DUE_1, CommitmentTally.callback(true, true, false)));
    }

    @Test
    void callbackWithoutHumanResponsibleDoesNotCount() {
        Result r = assertOrderIndependent(List.of(callback(DUE_1, null, SCHEDULED, 0), callback(DUE_1, null, MISSED, 200)));
        assertThat(r.facts()).isEmpty();
        assertThat(r.statuses()).containsEntry(DUE_1, MISSED);
    }

    // ------------------------------------------------------------------ promessas

    private static final UUID PROMISE = UUID.randomUUID();
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 10, 10);

    private static PromiseSignal promise(String previous, String status, int minute) {
        return new PromiseSignal(PROMISE, null, null, null, X, DUE_DATE, previous, status, T0.plusSeconds(60L * minute));
    }

    /** Soma (registros, desfecho) e situação final, aplicando como o serviço. */
    private static Map.Entry<CommitmentTally, String> runPromise(List<PromiseSignal> signals) {
        PromiseOutcome state = null;
        CommitmentTally total = CommitmentTally.ZERO;
        for (PromiseSignal s : signals) {
            PromiseChange change = CommitmentPolicy.applyPromise(state, s);
            if (change.registeredNow()) {
                total = total.plus(CommitmentTally.promiseRegistered());
            }
            if (change.before() != null) {
                total = total.plus(change.before().tally().negate());
            }
            total = total.plus(change.after().tally());
            state = change.after();
        }
        return Map.entry(total, state.status());
    }

    private static Map.Entry<CommitmentTally, String> assertPromiseOrderIndependent(List<PromiseSignal> signals) {
        Map.Entry<CommitmentTally, String> expected = runPromise(signals);
        for (List<PromiseSignal> order : permutations(signals)) {
            assertThat(runPromise(order)).as("ordem %s", order).isEqualTo(expected);
        }
        return expected;
    }

    @Test
    void keptPromiseIsRegisteredOnceAndDue() {
        var r = assertPromiseOrderIndependent(List.of(promise(null, "PENDING", 0), promise("PENDING", "KEPT", 60)));
        assertThat(r.getValue()).isEqualTo("KEPT");
        assertThat(r.getKey()).isEqualTo(new CommitmentTally(0, 0, 0, 1, 1, 1, 0));
    }

    @Test
    void reversalReopensAndLaterBreakCountsAsBroken() {
        var r = assertPromiseOrderIndependent(List.of(promise(null, "PENDING", 0), promise("PENDING", "KEPT", 60),
                promise("KEPT", "PENDING", 120), promise("PENDING", "BROKEN", 5000)));
        assertThat(r.getValue()).isEqualTo("BROKEN");
        assertThat(r.getKey()).isEqualTo(new CommitmentTally(0, 0, 0, 1, 1, 0, 1));
    }

    @Test
    void cancelledPromiseIsRegisteredButNotDue() {
        var r = assertPromiseOrderIndependent(List.of(promise(null, "PENDING", 0), promise("PENDING", "CANCELLED", 30)));
        assertThat(r.getKey()).isEqualTo(new CommitmentTally(0, 0, 0, 1, 0, 0, 0));
    }

    @Test
    void redeliveredRegistrationIsNotCountedTwice() {
        var r = runPromise(List.of(promise(null, "PENDING", 0), promise(null, "PENDING", 0)));
        assertThat(r.getKey().promisesRegistered()).isEqualTo(1);
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(new ArrayList<>(items));
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> order = new ArrayList<>();
                order.add(head);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }
}
