package br.com.gestorcobranca.costing.domain.repository;

import br.com.gestorcobranca.costing.domain.model.CostBand;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CostBandRepository {

    CostBand save(CostBand band);

    /** Grava já: o encerramento da vigência anterior precisa chegar ao banco antes da nova (exclusão de sobreposição). */
    CostBand saveAndFlush(CostBand band);

    Optional<CostBand> findById(UUID id);

    /** Vigências da faixa com o nome, bloqueadas para alteração (nova vigência ou encerramento concorrente). */
    List<CostBand> findAllByNameForUpdate(String name);

    List<CostBand> findAllByOrderByNameAscValidFromAsc();

    boolean existsByName(String name);
}
