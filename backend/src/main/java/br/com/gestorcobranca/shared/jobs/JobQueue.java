package br.com.gestorcobranca.shared.jobs;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila de trabalho durável em PostgreSQL ({@code shared.processing_job}), usada para tarefas
 * assíncronas e agendadas: processamento de importações, envio de mensagens, passos de régua,
 * geração de relatórios. Distribuída entre instâncias com {@code FOR UPDATE SKIP LOCKED}.
 * <p>
 * O enfileiramento participa da transação corrente: o job só existe se a operação que o
 * originou for confirmada.
 */
public interface JobQueue {

    /**
     * @param type          tipo do job, atendido por um {@link JobHandler} com o mesmo {@code type()}
     * @param payload       objeto serializável em JSON (somente ids e parâmetros, sem dados pessoais)
     * @param runAt         execução a partir de (null = imediatamente)
     * @param deduplicationKey chave opcional: se já houver job pendente com a mesma chave e tipo, não duplica
     * @return id do job (o existente, em caso de deduplicação)
     */
    UUID enqueue(String type, Object payload, Instant runAt, String deduplicationKey);

    default UUID enqueue(String type, Object payload) {
        return enqueue(type, payload, null, null);
    }

    /** Cancela jobs pendentes do tipo com a chave de deduplicação informada. */
    int cancelPending(String type, String deduplicationKey);

    /**
     * Renova o lease de um job em execução por mais um período ({@code app.jobs.lease}), em
     * transação própria. Handlers de longa duração (ex.: importação de arquivos grandes) devem
     * chamá-lo periodicamente; sem isso, a recuperação de leases expirados devolveria o job à fila
     * enquanto ele ainda executa.
     *
     * @param attempt tentativa corrente ({@link JobContext#attempt()})
     * @return falso se o job não está mais sob esta execução (lease perdido e job reivindicado de novo)
     */
    boolean renewLease(UUID jobId, int attempt);
}
