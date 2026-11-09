package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.Interaction;
import br.com.gestorcobranca.collection.domain.repository.InteractionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.UUID;

interface InteractionJpaRepository extends JpaRepository<Interaction, UUID>, InteractionRepository {

    @Override
    default Page<Interaction> findByDebtor(UUID debtorId, Collection<UUID> allowedCreditorIds, Pageable pageable) {
        return allowedCreditorIds == null ? byDebtor(debtorId, pageable)
                : byDebtorInCreditors(debtorId, allowedCreditorIds, pageable);
    }

    @Query("""
            select i from Interaction i where i.debtorId = :debtorId order by i.startedAt desc, i.id desc
            """)
    Page<Interaction> byDebtor(UUID debtorId, Pageable pageable);

    @Query("""
            select i from Interaction i where i.debtorId = :debtorId and i.creditorId in :creditorIds
             order by i.startedAt desc, i.id desc
            """)
    Page<Interaction> byDebtorInCreditors(UUID debtorId, Collection<UUID> creditorIds, Pageable pageable);
}
