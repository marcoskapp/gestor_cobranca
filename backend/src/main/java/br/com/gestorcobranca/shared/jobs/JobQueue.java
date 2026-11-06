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
     * @param runAt         execução a partir de (null = imediatamente, pelo relógio do banco — o mesmo do claim)
     * @param deduplicationKey chave opcional: se já houver job pendente com a mesma chave e tipo, não duplica
     * @return id do job (o existente, em caso de deduplicação)
     */
    UUID enqueue(String type, Object payload, Instant runAt, String deduplicationKey);

    default UUID enqueue(String type, Object payload) {
        return enqueue(type, payload, null, null);
    }

    /**
     * Enfileira um job imediato (como {@link #enqueue}, na transação corrente, obrigatória) e, <b>depois do
     * commit</b>, pede a esta instância que o execute já, sem esperar o próximo ciclo do worker. A execução imediata
     * reivindica o job pelo mesmo mecanismo do ciclo normal ({@code PENDING → RUNNING}, {@code locked_by} e
     * {@code attempts}, {@code SKIP LOCKED}): o job roda uma única vez mesmo que o ciclo normal ou outra instância o
     * alcance ao mesmo tempo. Sem capacidade de execução imediata na instância, ou se ela cair entre o commit e a
     * execução, o job continua {@code PENDING} e o ciclo normal o executa. Transação desfeita → nem job nem execução.
     * <p>
     * Para comandos curtos em que a latência importa (ex.: comandos à chamada em andamento na telefonia).
     *
     * @return id do job (o existente, em caso de deduplicação; job existente que não esteja pronto não é antecipado)
     */
    UUID enqueueAndDispatch(String type, Object payload, String deduplicationKey);

    /** Cancela jobs pendentes do tipo com a chave de deduplicação informada. */
    int cancelPending(String type, String deduplicationKey);
}
