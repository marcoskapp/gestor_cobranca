package br.com.gestorcobranca.scoring.application.port.out;

import br.com.gestorcobranca.scoring.domain.model.CaseFact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Fatos do relacionamento de cobrança ({@code scoring.case_fact}, append-only). */
public interface FactStore {

    /**
     * Grava o fato; idempotente por (evento de origem, tipo).
     *
     * @return false se já estava gravado (reentrega)
     */
    boolean record(UUID sourceEventId, CaseFact fact);

    /** Fatos dos devedores conhecidos a partir de {@code since} (inclusive), qualquer carteira. */
    List<CaseFact> factsOfDebtors(Collection<UUID> debtorIds, Instant since);

    /** Retenção: apaga até {@code limit} fatos conhecidos antes de {@code before}. */
    int purgeOlderThan(Instant before, int limit);

    /** Anonimização LGPD do devedor. */
    int deleteByDebtor(UUID debtorId);
}
