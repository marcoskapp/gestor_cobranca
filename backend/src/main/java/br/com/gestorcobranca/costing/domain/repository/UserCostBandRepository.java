package br.com.gestorcobranca.costing.domain.repository;

import br.com.gestorcobranca.costing.domain.model.UserCostBand;

import java.util.List;
import java.util.UUID;

public interface UserCostBandRepository {

    UserCostBand save(UserCostBand band);

    /** Grava já: o encerramento da vigência anterior precisa chegar ao banco antes da nova (exclusão de sobreposição). */
    UserCostBand saveAndFlush(UserCostBand band);

    /** Vigências da pessoa, bloqueadas para alteração. */
    List<UserCostBand> findAllByUserIdForUpdate(UUID userId);

    List<UserCostBand> findAllByUserIdOrderByValidFromAsc(UUID userId);

    List<UserCostBand> findAllByOrderByUserIdAscValidFromAsc();
}
