package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.repository.TitleLedgerEntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Sem métodos de alteração/remoção: o razão é append-only também na aplicação. */
interface TitleLedgerEntryJpaRepository extends Repository<TitleLedgerEntry, UUID>, TitleLedgerEntryRepository {

    @Override
    <S extends TitleLedgerEntry> List<S> saveAll(Iterable<S> entries);

    @Override
    List<TitleLedgerEntry> findByTitleIdOrderByOccurredAtAscIdAsc(UUID titleId);

    @Override
    Page<TitleLedgerEntry> findByTitleId(UUID titleId, Pageable pageable);
}
