package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSubjectRequestTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-01T13:00:00Z");
    private static final Instant DUE = Instant.parse("2026-10-17T03:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    static DataSubjectRequest request(RequestType type) {
        return new DataSubjectRequest(type, RequestChannel.EMAIL, " Maria Silva ", "52998224725", "h".repeat(64),
                "maria@exemplo.com", "Quero meus dados", RECEIVED, DUE);
    }

    @Test
    void happyPathForAccess() {
        DataSubjectRequest r = request(RequestType.ACCESS);
        assertThat(r.getRequesterName()).isEqualTo("Maria Silva");
        UUID debtor = UUID.randomUUID();
        r.verifyIdentity(debtor, ACTOR, RECEIVED.plusSeconds(60));
        r.start(ACTOR);
        assertThatThrownBy(() -> r.complete("pronto", RECEIVED)).hasFieldOrPropertyWithValue("code", "dsr.execution_required");
        r.attachResult("tenants/x/data-subject-exports/f.json", "s".repeat(64), RECEIVED.plusSeconds(120));
        assertThatThrownBy(r::requireExecutable).hasFieldOrPropertyWithValue("code", "dsr.already_executed");
        r.complete("Arquivo enviado ao titular", RECEIVED.plusSeconds(180));
        assertThat(r.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(r.getDebtorId()).isEqualTo(debtor);
        assertThat(r.getCompletedAt()).isNotNull();
    }

    @Test
    void transitionsAreValidated() {
        DataSubjectRequest r = request(RequestType.OPPOSITION);
        assertThatThrownBy(() -> r.start(ACTOR)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "dsr.invalid_transition");
        assertThatThrownBy(() -> r.complete("x", RECEIVED)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(r::requireExecutable).hasFieldOrPropertyWithValue("code", "dsr.not_in_progress");
        r.reject("Identidade não confirmada", RECEIVED.plusSeconds(10));
        assertThat(r.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThatThrownBy(() -> r.verifyIdentity(UUID.randomUUID(), ACTOR, RECEIVED)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> r.assign(ACTOR)).hasFieldOrPropertyWithValue("code", "dsr.closed");
    }

    @Test
    void typesActingOnDataRequireTheDebtor() {
        DataSubjectRequest anonymization = request(RequestType.ANONYMIZATION);
        assertThatThrownBy(() -> anonymization.verifyIdentity(null, ACTOR, RECEIVED))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "dsr.debtor_required");

        DataSubjectRequest confirmation = request(RequestType.CONFIRMATION);
        confirmation.verifyIdentity(null, ACTOR, RECEIVED);
        confirmation.start(null);
        assertThatThrownBy(confirmation::requireExecutable).hasFieldOrPropertyWithValue("code", "dsr.not_executable");
        confirmation.complete("Não há tratamento de dados do requerente", RECEIVED.plusSeconds(5));
        assertThat(confirmation.getStatus()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    void rejectionRequiresJustification() {
        DataSubjectRequest r = request(RequestType.CORRECTION);
        assertThatThrownBy(() -> r.reject(" ", RECEIVED)).hasFieldOrPropertyWithValue("code", "dsr.response_required");
    }

    @Test
    void overdueIsFlaggedOnceAndOnlyForOpenRequests() {
        DataSubjectRequest r = request(RequestType.ACCESS);
        assertThat(r.markOverdueIfLate(DUE.minusSeconds(1))).isFalse();
        assertThat(r.markOverdueIfLate(DUE)).isTrue();
        assertThat(r.markOverdueIfLate(DUE.plusSeconds(60))).isFalse();
        assertThat(r.isOverdue()).isTrue();

        DataSubjectRequest closed = request(RequestType.ACCESS);
        closed.reject("Duplicada", RECEIVED);
        assertThat(closed.markOverdueIfLate(DUE.plusSeconds(1))).isFalse();
    }

    @Test
    void statusTransitionTable() {
        assertThat(RequestStatus.RECEIVED.allowedTransitions())
                .containsExactlyInAnyOrder(RequestStatus.IDENTITY_VERIFIED, RequestStatus.REJECTED);
        assertThat(RequestStatus.IN_PROGRESS.allowedTransitions())
                .containsExactlyInAnyOrder(RequestStatus.COMPLETED, RequestStatus.REJECTED);
        assertThat(RequestStatus.COMPLETED.allowedTransitions()).isEmpty();
        assertThat(RequestStatus.REJECTED.isFinal()).isTrue();
    }
}
