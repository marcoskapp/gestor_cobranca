package br.com.gestorcobranca.negotiation.application.port.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O que ainda pode baixar parcelas do acordo e o negotiation não conhece: pagamentos aguardando a revisão de
 * outra pessoa (externos acima do limite e créditos de extrato importado — decisão 1451) e alocações já confirmadas ainda não baixadas (em trânsito).
 * Implementada pelo módulo payment (ADR-0010), lida na mesma transação do job de quebra — a fonte é o
 * próprio payment, sem cópia nem atraso de evento.
 * <p>
 * Consulta de sistema: sem permissão nem escopo de credor (a RLS do tenant corrente vale); devolve só ids,
 * instantes e valores — nada pessoal.
 */
public interface PaymentReviewHolds {

    /**
     * @param agreementId   acordo
     * @param reviewedSince revisões concluídas a partir deste instante (nulo = nenhuma)
     */
    PaymentHold holdFor(UUID agreementId, Instant reviewedSince);

    /**
     * @param pending           pagamentos {@code INFORMED} em revisão com o acordo como destino, do mais antigo ao mais recente
     * @param inTransit         ids das alocações ao acordo confirmadas e ainda sem retorno do negotiation (o
     *                          {@code paymentId} do {@code PaymentConfirmed})
     * @param reviewed          pagamentos com o acordo como destino revisados por segunda pessoa desde {@code reviewedSince}
     */
    record PaymentHold(List<PendingReview> pending, List<UUID> inTransit, List<ReviewOutcome> reviewed) {

        public PaymentHold {
            pending = pending == null ? List.of() : List.copyOf(pending);
            inTransit = inTransit == null ? List.of() : List.copyOf(inTransit);
            reviewed = reviewed == null ? List.of() : List.copyOf(reviewed);
        }

        public BigDecimal pendingAmount() {
            return pending.stream().map(PendingReview::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    record PendingReview(UUID paymentId, Instant informedAt, BigDecimal amount) {
    }

    /** @param approved {@code true} = aprovado (confirmado); {@code false} = recusado */
    record ReviewOutcome(UUID paymentId, boolean approved, Instant reviewedAt) {
    }
}
