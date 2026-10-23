package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EnrollmentJpaRepository extends JpaRepository<Enrollment, UUID>, JpaSpecificationExecutor<Enrollment>,
        EnrollmentRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Enrollment e where e.id = :id")
    Optional<Enrollment> findByIdForUpdate(UUID id);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from Enrollment e where e.debtorId = :debtorId and e.exclusivityGroup = :exclusivityGroup
               and e.status in (br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.ACTIVE,
                                br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.PAUSED,
                                br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.FAILED)
            """)
    List<Enrollment> findRunningInGroupForUpdate(UUID debtorId, String exclusivityGroup);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from Enrollment e where e.debtorId = :debtorId
               and e.status in (br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.ACTIVE,
                                br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.PAUSED,
                                br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.FAILED)
             order by e.id
            """)
    List<Enrollment> findRunningByDebtorForUpdate(UUID debtorId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from Enrollment e where e.caseId = :caseId
               and e.status in (br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.ACTIVE,
                                br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.PAUSED,
                                br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus.FAILED)
             order by e.id
            """)
    List<Enrollment> findRunningByCaseForUpdate(UUID caseId);

    @Override
    @Query("""
            select e from Enrollment e where e.debtorId = :debtorId and e.creditorId = :creditorId
               and e.enrolledAt <= :at and (e.observeUntil is null or e.observeUntil >= :at)
             order by e.id
            """)
    List<Enrollment> findObservingByDebtorAndCreditor(UUID debtorId, UUID creditorId, Instant at);

    @Override
    default Optional<Enrollment> findLatestByCampaignAndCase(UUID campaignId, UUID caseId) {
        return findFirstByCampaignIdAndCaseIdOrderByEnrolledAtDesc(campaignId, caseId);
    }

    Optional<Enrollment> findFirstByCampaignIdAndCaseIdOrderByEnrolledAtDesc(UUID campaignId, UUID caseId);

    @Override
    @Query(value = """
            select id from campaign.enrollment
             where status = 'PAUSED' and resume_at is not null and resume_at <= :now
             order by resume_at limit :limit
            """, nativeQuery = true)
    List<UUID> findIdsDueToResume(Instant now, int limit);

    @Override
    @Query(value = """
            select id from campaign.enrollment
             where status = 'HOLDOUT' and observe_until <= :now
             order by observe_until limit :limit
            """, nativeQuery = true)
    List<UUID> findHoldoutIdsFinished(Instant now, int limit);

    @Override
    @Query(value = """
            select id from campaign.enrollment
             where campaign_id = :campaignId and status = 'ACTIVE' and next_run_at < :before
             order by next_run_at limit :limit
               for update skip locked
            """, nativeQuery = true)
    List<UUID> lockOverdueActiveIds(UUID campaignId, Instant before, int limit);

    @Override
    default List<UUID> findIdsByCampaignAndStatus(UUID campaignId, Collection<EnrollmentStatus> statuses, UUID afterId,
                                                  int limit) {
        return idsByCampaignAndStatus(campaignId, statuses.stream().map(Enum::name).toList(),
                afterId == null ? new UUID(0L, 0L) : afterId, limit);
    }

    @Query(value = """
            select id from campaign.enrollment
             where campaign_id = :campaignId and status in (:statuses) and id > :afterId
             order by id limit :limit
            """, nativeQuery = true)
    List<UUID> idsByCampaignAndStatus(UUID campaignId, Collection<String> statuses, UUID afterId, int limit);

    @Override
    default Page<Enrollment> search(UUID campaignId, EnrollmentStatus status, UUID debtorId, UUID caseId,
                                    Pageable pageable) {
        Specification<Enrollment> spec = (root, query, cb) -> cb.equal(root.get("campaignId"), campaignId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (debtorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorId"), debtorId));
        }
        if (caseId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("caseId"), caseId));
        }
        return findAll(spec, pageable);
    }
}
