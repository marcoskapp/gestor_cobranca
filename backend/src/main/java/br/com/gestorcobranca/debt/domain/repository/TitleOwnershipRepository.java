package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.TitleOwnership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TitleOwnershipRepository {

    TitleOwnership save(TitleOwnership ownership);

    /**
     * Grava imediatamente. Necessário ao encerrar um período antes de abrir o seguinte: o
     * Hibernate executa inclusões antes de alterações no flush, o que violaria o índice único
     * de período aberto por título.
     */
    TitleOwnership saveAndFlush(TitleOwnership ownership);

    Optional<TitleOwnership> findByTitleIdAndReleasedAtIsNull(UUID titleId);

    List<TitleOwnership> findByTitleIdOrderByAssignedAtAsc(UUID titleId);
}
