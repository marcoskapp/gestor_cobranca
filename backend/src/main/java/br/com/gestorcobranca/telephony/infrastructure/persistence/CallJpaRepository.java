package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallSearchCriteria;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CallJpaRepository extends JpaRepository<Call, UUID>, JpaSpecificationExecutor<Call>, CallRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Call c where c.id = :id")
    Optional<Call> findByIdForUpdate(UUID id);

    @Override
    Optional<Call> findByProviderAndProviderCallId(ProviderType provider, String providerCallId);

    @Override
    @Query("""
            select c from Call c where c.operatorId = :operatorId
               and c.interactionState = br.com.gestorcobranca.telephony.domain.model.InteractionState.AWAITING_TABULATION
             order by c.endedAt
            """)
    List<Call> findAwaitingTabulation(UUID operatorId);

    @Override
    @Query(value = """
            select count(*) from telephony.phone_call
             where campaign_id = :campaignId
               and (status in ('QUEUED', 'DIALING', 'RINGING') or (status = 'ANSWERED' and connected_at is null))
            """, nativeQuery = true)
    long countUnconnectedActiveByCampaign(UUID campaignId);

    @Override
    @Query(value = """
            select id from telephony.phone_call
             where campaign_id = :campaignId and status in ('QUEUED', 'DIALING', 'RINGING', 'ANSWERED')
            """, nativeQuery = true)
    List<UUID> findActiveIdsByCampaign(UUID campaignId);

    @Override
    default List<Call> findIdentifiedByNumberHash(String numberHash, int limit) {
        return identifiedByNumberHash(numberHash, PageRequest.of(0, limit));
    }

    @Query("""
            select c from Call c where c.numberHash = :numberHash and c.caseId is not null
             order by c.queuedAt desc
            """)
    List<Call> identifiedByNumberHash(String numberHash, Pageable pageable);

    @Override
    @Query(value = """
            select id from telephony.phone_call where debtor_id = :debtorId
               and (number_encrypted is not null or number_hash is not null)
            """, nativeQuery = true)
    List<UUID> findIdsWithNumberByDebtor(UUID debtorId);

    @Override
    @Query(value = """
            select id from telephony.phone_call
             where status in ('QUEUED', 'DIALING', 'RINGING', 'ANSWERED') and queued_at < :before
             order by queued_at limit :limit
            """, nativeQuery = true)
    List<UUID> findStaleActiveIds(Instant before, int limit);

    @Override
    default Page<Call> search(CallSearchCriteria c, Pageable pageable) {
        Specification<Call> spec = Specification.unrestricted();
        if (c.allowedCreditorIds() != null) {
            spec = spec.and((root, query, cb) -> {
                var inScope = c.allowedCreditorIds().isEmpty() ? cb.disjunction()
                        : root.get("creditorId").in(c.allowedCreditorIds());
                return c.includeWithoutCreditor() ? cb.or(inScope, cb.isNull(root.get("creditorId"))) : inScope;
            });
        }
        spec = eq(spec, "creditorId", c.creditorId());
        spec = eq(spec, "caseId", c.caseId());
        spec = eq(spec, "debtorId", c.debtorId());
        spec = eq(spec, "operatorId", c.operatorId());
        spec = eq(spec, "campaignId", c.campaignId());
        if (c.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status").as(String.class), c.status()));
        }
        if (c.result() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("result").as(String.class), c.result()));
        }
        if (c.direction() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("direction").as(String.class), c.direction()));
        }
        if (c.from() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("queuedAt"), c.from()));
        }
        if (c.to() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("queuedAt"), c.to()));
        }
        return findAll(spec, pageable);
    }

    private static Specification<Call> eq(Specification<Call> spec, String field, Object value) {
        return value == null ? spec : spec.and((root, query, cb) -> cb.equal(root.get(field), value));
    }
}
