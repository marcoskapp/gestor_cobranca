package br.com.gestorcobranca.incentive.application.port.out;

import java.util.UUID;

/**
 * Serializa, até o fim da transação, o processamento dos eventos do mesmo retorno (tarefa) ou da
 * mesma promessa: eles chegam em paralelo e em qualquer ordem, e a situação é recalculada a partir
 * da anterior. Implementação com advisory lock transacional do PostgreSQL.
 */
public interface CommitmentLock {

    void lockCallback(UUID taskId);

    void lockPromise(UUID promiseId);
}
