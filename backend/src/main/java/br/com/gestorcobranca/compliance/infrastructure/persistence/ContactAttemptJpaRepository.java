package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.ContactAttempt;
import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import br.com.gestorcobranca.compliance.domain.repository.ContactAttemptRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface ContactAttemptJpaRepository extends JpaRepository<ContactAttempt, UUID>, ContactAttemptRepository {

    /** Usa o índice {@code ix_contact_attempt_gate (tenant, devedor, canal, instante)}. */
    @Override
    @Query("""
            select a.attemptedAt from ContactAttempt a
             where a.debtorId = :debtorId and a.channel = :channel and a.attemptedAt >= :since
            """)
    List<Instant> findAttemptTimes(UUID debtorId, ContactChannel channel, Instant since);

    @Override
    default Page<ContactAttempt> findByDebtor(UUID debtorId, Collection<UUID> allowedCreditorIds, Pageable pageable) {
        if (allowedCreditorIds == null) {
            return findByDebtorIdOrderByAttemptedAtDesc(debtorId, pageable);
        }
        return findByDebtorIdAndCreditorIdInOrderByAttemptedAtDesc(debtorId, allowedCreditorIds, pageable);
    }

    Page<ContactAttempt> findByDebtorIdOrderByAttemptedAtDesc(UUID debtorId, Pageable pageable);

    Page<ContactAttempt> findByDebtorIdAndCreditorIdInOrderByAttemptedAtDesc(UUID debtorId, Collection<UUID> creditorIds,
                                                                            Pageable pageable);

    @Override
    List<ContactAttempt> findByDebtorIdOrderByAttemptedAtDesc(UUID debtorId);
}
