package br.com.gestorcobranca.shared.jobs;

/**
 * Processador de um tipo de job. Executado pelo {@link JobWorker} no tenant do job, como ator
 * de sistema, dentro de uma transação (ou sem ela, com {@link #transactional()} falso). Deve ser
 * idempotente: um job pode ser reexecutado se a instância cair durante o processamento.
 *
 * @param <P> tipo do payload (desserializado de JSON)
 */
public interface JobHandler<P> {

    String type();

    Class<P> payloadType();

    void handle(JobContext context, P payload);

    /** Número máximo de tentativas antes de mover o job para DEAD. */
    default int maxAttempts() {
        return 8;
    }

    /**
     * Exceções que não devem ser tentadas novamente (erro permanente de dados).
     * Por padrão, apenas {@link PermanentJobFailure}.
     */
    default boolean isPermanent(RuntimeException e) {
        return e instanceof PermanentJobFailure;
    }

    /**
     * {@code true} (padrão): o handler roda numa transação do worker, confirmada junto com a baixa do job
     * ({@code DONE}). {@code false}: o handler roda só com o tenant e o ator definidos, sem transação, e abre
     * as próprias transações curtas — para chamadas longas a provedores externos (telefonia, IA, armazenamento)
     * que não devem segurar conexão nem lock do banco (decisão 852). Nesse modo a baixa do job é feita depois,
     * em transação própria; o handler continua idempotente por etapa.
     */
    default boolean transactional() {
        return true;
    }
}
