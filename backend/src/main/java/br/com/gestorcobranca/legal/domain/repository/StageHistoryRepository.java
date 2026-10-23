package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.StageHistoryEntry;

import java.util.List;
import java.util.UUID;

public interface StageHistoryRepository {

    StageHistoryEntry save(StageHistoryEntry entry);

    List<StageHistoryEntry> findByMatterIdOrderByOccurredAtAscCreatedAtAsc(UUID matterId);
}
