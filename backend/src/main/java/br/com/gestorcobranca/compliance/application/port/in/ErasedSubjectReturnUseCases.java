package br.com.gestorcobranca.compliance.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tentativas de retorno do titular eliminado a pedido (decisão 1471) e a revisão humana: manter a recusa ou liberar o
 * retorno (decisão 1604). Exigem {@code DATA_SUBJECT_REQUEST_MANAGE} e acesso a todos os credores (a tentativa cita o
 * credor e o lote). Liberar exige outra pessoa que não a que executou a eliminação, e motivo.
 */
public interface ErasedSubjectReturnUseCases {

    ReturnsView of(UUID requestId);

    /** Mantém a recusa: as tentativas pendentes ficam revisadas. */
    ReturnsView keep(UUID requestId);

    /** Libera o retorno do titular na assessoria inteira, com motivo; as tentativas pendentes ficam revisadas. */
    ReturnsView release(UUID requestId, String reason);

    /**
     * @param refusing   o retorno do titular é recusado hoje (eliminação a pedido com índice e sem liberação)
     * @param releasable o ator pode liberar (não foi quem executou a eliminação)
     * @param release    a liberação, se houver
     */
    record ReturnsView(UUID requestId, boolean refusing, boolean releasable, List<ReturnView> attempts,
                       ReleaseView release) {
    }

    /**
     * @param source  IMPORT, DEBTOR_FORM ou CO_OBLIGOR_FORM
     * @param outcome KEPT, RELEASED ou nulo (sem revisão)
     */
    record ReturnView(UUID id, Instant attemptedAt, String source, UUID creditorId, UUID batchId, int lines,
                      String outcome, Instant reviewedAt, UUID reviewedBy) {
    }

    record ReleaseView(Instant releasedAt, UUID releasedBy, String reason) {
    }
}
