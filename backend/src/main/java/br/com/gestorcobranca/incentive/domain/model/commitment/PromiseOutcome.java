package br.com.gestorcobranca.incentive.domain.model.commitment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Situação atual de uma promessa de pagamento simples, materializada dos eventos
 * {@code PromiseStatusChanged}. A promessa volta de cumprida a pendente quando o pagamento é
 * estornado, por isso vale a situação do evento mais recente ({@code statusAt}); eventos mais
 * antigos que chegam depois não a alteram.
 *
 * @param status       PENDING, KEPT, BROKEN ou CANCELLED (texto do produtor)
 * @param registeredAt instante do registro, quando o evento de registro já foi contado
 */
public record PromiseOutcome(UUID promiseId, UUID caseId, UUID creditorId, UUID portfolioId, UUID operatorId,
                             LocalDate dueDate, String status, Instant statusAt, Instant registeredAt) {

    public static final String KEPT = "KEPT";
    public static final String BROKEN = "BROKEN";

    public PromiseOutcome {
        Objects.requireNonNull(promiseId, "promiseId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusAt, "statusAt");
    }

    /** Contribuição para os fatos por data prometida (zero sem operador ou sem desfecho). */
    public CommitmentTally tally() {
        if (operatorId == null || dueDate == null) {
            return CommitmentTally.ZERO;
        }
        return CommitmentTally.promiseOutcome(KEPT.equals(status), BROKEN.equals(status));
    }
}
