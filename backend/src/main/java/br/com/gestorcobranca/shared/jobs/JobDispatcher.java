package br.com.gestorcobranca.shared.jobs;

import java.util.UUID;

/**
 * Execução imediata, nesta instância, de um job já confirmado ({@link JobWorker}). Usado por
 * {@link JobQueue#enqueueAndDispatch} depois do commit. Nunca bloqueia nem lança: sem capacidade, ou com o job já
 * reivindicado por outra execução, não faz nada e o job segue pelo ciclo normal do worker.
 */
interface JobDispatcher {

    void dispatch(UUID jobId);
}
