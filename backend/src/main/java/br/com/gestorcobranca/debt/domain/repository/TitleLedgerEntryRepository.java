package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/** Razão do título: somente inclusão e leitura. */
public interface TitleLedgerEntryRepository {

    <S extends TitleLedgerEntry> List<S> saveAll(Iterable<S> entries);

    /** Todos os lançamentos do título, em ordem cronológica. */
    List<TitleLedgerEntry> findByTitleIdOrderByOccurredAtAscIdAsc(UUID titleId);

    Page<TitleLedgerEntry> findByTitleId(UUID titleId, Pageable pageable);
}
