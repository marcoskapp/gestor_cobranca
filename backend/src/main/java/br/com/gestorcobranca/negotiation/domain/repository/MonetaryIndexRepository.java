package br.com.gestorcobranca.negotiation.domain.repository;

import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndex;
import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndexRate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Índices de correção e suas variações mensais (tabela manual por tenant). */
public interface MonetaryIndexRepository {

    MonetaryIndex save(MonetaryIndex index);

    Optional<MonetaryIndex> findByCode(String code);

    List<MonetaryIndex> findAllIndexes();

    boolean existsByCode(String code);

    MonetaryIndexRate saveRate(MonetaryIndexRate rate);

    /** Variações do índice em ordem de competência. */
    List<MonetaryIndexRate> findRates(UUID indexId);
}
