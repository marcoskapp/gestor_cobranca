package br.com.gestorcobranca.assistant.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Descarte do conteúdo textual da trilha (entrada, contexto e resposta) — única alteração permitida
 * numa linha de turno (append-only com exceção controlada por gatilho no banco).
 */
public interface TurnContentStore {

    /** Retenção: turnos mais antigos que o corte, em lote. @return turnos descartados */
    int purgeOlderThan(Instant cutoff, int limit);

    /** Anonimização do devedor. @return turnos descartados */
    int purgeByDebtor(UUID debtorId);
}
