package br.com.gestorcobranca.debt.application.port.in;

import java.time.LocalDate;
import java.util.UUID;

/**
 * <strong>Porta interna</strong> (sem endpoint web) para o módulo legal registrar interrupções da
 * prescrição decorrentes de medidas extrajudiciais (protesto) e de andamentos processuais
 * (citação). Idempotente por (título, tipo, data): repetir o registro devolve o existente com
 * {@code registered = false}, seguro para consumidores de evento com reentrega.
 * <p>
 * A evidência, se houver, é um arquivo já gravado pelo módulo chamador no {@code FileStorage} do
 * tenant corrente (a chave é validada contra o tenant). Exige escopo sobre o credor do título.
 */
public interface PrescriptionInterruptionPort {

    InterruptionResult register(LegalInterruption interruption);

    /**
     * @param type              PROTEST, CITATION, ACKNOWLEDGEMENT ou OTHER
     * @param originReferenceId registro de origem no módulo legal (medida, andamento)
     * @param evidence          arquivo de evidência já armazenado (opcional)
     */
    record LegalInterruption(UUID titleId, String type, LocalDate date, String reference, String notes,
                             UUID originReferenceId, StoredEvidence evidence) {
    }

    /** Metadados de {@code FileStorage.StoredFile}. */
    record StoredEvidence(String key, String fileName, String contentType, long size, String sha256) {
    }

    /**
     * @param registered              falso quando a interrupção já existia (repetição idempotente)
     * @param executionEstimatedDate  data estimada da pretensão executiva após o registro (nula sem regra aplicável)
     * @param collectionEstimatedDate data estimada da pretensão de cobrança após o registro (nula sem regra)
     */
    record InterruptionResult(UUID interruptionId, UUID titleId, boolean registered, LocalDate executionEstimatedDate,
                              LocalDate collectionEstimatedDate) {
    }
}
