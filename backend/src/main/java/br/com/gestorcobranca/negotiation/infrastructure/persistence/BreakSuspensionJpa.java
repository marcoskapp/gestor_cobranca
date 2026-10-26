package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspension;
import br.com.gestorcobranca.negotiation.domain.repository.BreakSuspensionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BreakSuspensionJpa extends JpaRepository<BreakSuspension, UUID> {

    @Query("""
            select s from BreakSuspension s
             where s.agreementId = :agreementId
               and s.status = br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspensionStatus.ACTIVE
            """)
    Optional<BreakSuspension> active(@Param("agreementId") UUID agreementId);

    Optional<BreakSuspension> findFirstByAgreementIdOrderByStartedAtDescIdDesc(UUID agreementId);

    @Query("""
            select s from BreakSuspension s
             where s.agreementId in :agreementIds
               and s.startedAt = (select max(o.startedAt) from BreakSuspension o where o.agreementId = s.agreementId)
             order by s.agreementId
            """)
    List<BreakSuspension> latestByAgreements(@Param("agreementIds") Collection<UUID> agreementIds);

    @Query("""
            select count(s) > 0 from BreakSuspension s
             where s.triggerInstallmentId = :installmentId
               and s.endReason in (br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspensionEndReason.REJECTED,
                                   br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspensionEndReason.DEADLINE)
            """)
    boolean blockingEnd(@Param("installmentId") UUID installmentId);

    @Query("""
            select s.agreementId from BreakSuspension s
             where s.status = br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspensionStatus.ACTIVE
               and s.agreementId > :afterId
             order by s.agreementId
             limit :limit
            """)
    List<UUID> activeAgreementIds(@Param("afterId") UUID afterId, @Param("limit") int limit);
}

@Repository
class JpaBreakSuspensionRepository implements BreakSuspensionRepository {

    private static final UUID FIRST = new UUID(0L, 0L);

    private final BreakSuspensionJpa suspensions;

    JpaBreakSuspensionRepository(BreakSuspensionJpa suspensions) {
        this.suspensions = suspensions;
    }

    @Override
    public BreakSuspension save(BreakSuspension suspension) {
        return suspensions.save(suspension);
    }

    @Override
    public Optional<BreakSuspension> findActive(UUID agreementId) {
        return suspensions.active(agreementId);
    }

    @Override
    public Optional<BreakSuspension> findLatest(UUID agreementId) {
        return suspensions.findFirstByAgreementIdOrderByStartedAtDescIdDesc(agreementId);
    }

    @Override
    public List<BreakSuspension> findLatestByAgreements(Collection<UUID> agreementIds) {
        return agreementIds.isEmpty() ? List.of() : suspensions.latestByAgreements(agreementIds);
    }

    @Override
    public boolean existsBlockingEnd(UUID triggerInstallmentId) {
        return suspensions.blockingEnd(triggerInstallmentId);
    }

    @Override
    public List<UUID> findActiveAgreementIds(UUID afterId, int limit) {
        return suspensions.activeAgreementIds(afterId == null ? FIRST : afterId, limit);
    }
}
