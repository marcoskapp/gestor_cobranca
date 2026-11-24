package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPasswordHistoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface CreditorPasswordHistoryJpaRepository extends JpaRepository<CreditorPasswordHistoryEntry, UUID> {

    @Query("select h from CreditorPasswordHistoryEntry h where h.userId = :userId order by h.createdAt desc")
    List<CreditorPasswordHistoryEntry> findRecent(UUID userId, Pageable page);
}
