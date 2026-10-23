package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.StageHistoryEntry;
import br.com.gestorcobranca.legal.domain.repository.StageHistoryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface StageHistoryJpaRepository extends JpaRepository<StageHistoryEntry, UUID>, StageHistoryRepository {
}
