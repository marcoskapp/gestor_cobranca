package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.quality.ConductOccurrence;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface ConductOccurrenceJpa extends JpaRepository<ConductOccurrence, UUID> {

    List<ConductOccurrence> findByUserIdAndOccurredOnBetweenOrderByOccurredOnAsc(UUID userId, LocalDate from, LocalDate to);

    @Query("""
            select o from ConductOccurrence o
            where (:allUsers = true or o.userId in :userIds) and o.occurredOn between :from and :to
            order by o.occurredOn desc, o.createdAt desc
            """)
    Page<ConductOccurrence> search(boolean allUsers, Collection<UUID> userIds, LocalDate from, LocalDate to,
                                   Pageable pageable);
}
