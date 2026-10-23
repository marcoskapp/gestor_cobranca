package br.com.gestorcobranca.ingestion.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida do lote.
 * <pre>
 * IMPORT:        RECEIVED → PROCESSING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED
 * VALIDATE_ONLY: RECEIVED → VALIDATING → VALIDATED | FAILED
 * ambos:         RECEIVED → CANCELLED | FAILED;  PROCESSING/VALIDATING → CANCELLED (pedido do usuário,
 *                ao fim do bloco corrente)
 * </pre>
 */
public enum ImportBatchStatus {
    RECEIVED,
    VALIDATING,
    VALIDATED,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED;

    private static final Set<ImportBatchStatus> TERMINAL = EnumSet.of(VALIDATED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED,
            CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Importação concluída: o conteúdo do arquivo foi gravado na carteira. */
    public boolean isSuccessfulImport() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS;
    }
}
