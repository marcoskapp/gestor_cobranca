package br.com.gestorcobranca.analytics.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Reconstrução dos snapshots da assessoria corrente, na transação do chamador (delete + insert:
 * leitores veem a versão anterior até o commit). Atualizações simultâneas da mesma assessoria são
 * serializadas por bloqueio consultivo.
 */
public interface SnapshotStore {

    /** Bloqueia (até o fim da transação) atualizações simultâneas da assessoria e amplia o tempo limite. */
    void lockForRefresh(Duration statementTimeout);

    /** @return linhas gravadas */
    long refresh(String dataset, LocalDate referenceDate, String zoneId);

    void saveState(String dataset, Instant dataUntil, Instant refreshedAt, long durationMs, long rows);

    Optional<Instant> lastRefreshedAt();
}
