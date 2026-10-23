package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.ReversalNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReversalNoticeJpa extends JpaRepository<ReversalNotice, UUID> {
    Optional<ReversalNotice> findByReversalId(UUID reversalId);

    List<ReversalNotice> findByAllocationIdOrderByCreatedAtAsc(UUID allocationId);
}
