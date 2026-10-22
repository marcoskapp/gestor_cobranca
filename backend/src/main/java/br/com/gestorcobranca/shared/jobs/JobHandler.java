package br.com.gestorcobranca.shared.jobs;

/**
 * Processador de um tipo de job. Executado pelo {@link JobWorker} no tenant do job, como ator
 * de sistema, dentro de uma transação. Deve ser idempotente: um job pode ser reexecutado se a
 * instância cair durante o processamento.
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
}
