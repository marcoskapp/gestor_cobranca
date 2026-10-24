package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.domain.model.AccessLink;
import br.com.gestorcobranca.portal.domain.repository.AccessLinkRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AccessLinkJpaRepository extends JpaRepository<AccessLink, UUID>, AccessLinkRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AccessLink l where l.tokenHash = :tokenHash")
    Optional<AccessLink> findByTokenHashForUpdate(String tokenHash);

    @Override
    @Query("""
            select l from AccessLink l
             where l.configurationId = :configurationId and l.debtorId = :debtorId
               and l.usedAt is null and l.revokedAt is null
            """)
    List<AccessLink> findOpen(UUID configurationId, UUID debtorId);
}
