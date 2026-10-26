package br.com.gestorcobranca.pendingwork.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Registro diário das pendências observadas por usuário e tipo (insumo do log de negócio): a primeira
 * observação do dia grava a linha; as seguintes não alteram nada.
 */
public interface PendingWorkObservationStore {

    /** @return verdadeiro se esta foi a primeira observação do tipo para o usuário no dia */
    boolean recordFirst(UUID userId, LocalDate day, String itemType, long total, long overdue, Instant observedAt);

    /** Remove observações anteriores ao dia informado (retenção); devolve o número de linhas. */
    int purgeBefore(LocalDate day);
}
