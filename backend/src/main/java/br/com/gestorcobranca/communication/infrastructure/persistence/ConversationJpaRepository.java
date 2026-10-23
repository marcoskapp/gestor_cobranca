package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationSearchCriteria;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ConversationJpaRepository extends JpaRepository<Conversation, UUID>, JpaSpecificationExecutor<Conversation>,
        ConversationRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForUpdate(UUID id);

    @Override
    default Optional<Conversation> findOpen(UUID channelAccountId, String contactHash, UUID creditorId) {
        return creditorId == null ? findOpenWithoutCreditor(channelAccountId, contactHash)
                : findOpenForCreditor(channelAccountId, contactHash, creditorId);
    }

    @Query("""
            select c from Conversation c where c.channelAccountId = :channelAccountId and c.contactHash = :contactHash
               and c.creditorId is null
               and c.status = br.com.gestorcobranca.communication.domain.model.ConversationStatus.OPEN
            """)
    Optional<Conversation> findOpenWithoutCreditor(UUID channelAccountId, String contactHash);

    @Query("""
            select c from Conversation c where c.channelAccountId = :channelAccountId and c.contactHash = :contactHash
               and c.creditorId = :creditorId
               and c.status = br.com.gestorcobranca.communication.domain.model.ConversationStatus.OPEN
            """)
    Optional<Conversation> findOpenForCreditor(UUID channelAccountId, String contactHash, UUID creditorId);

    @Override
    @Query("""
            select c from Conversation c where c.channelAccountId = :channelAccountId and c.contactHash = :contactHash
               and c.status = br.com.gestorcobranca.communication.domain.model.ConversationStatus.OPEN
             order by coalesce(c.lastOutboundAt, c.lastMessageAt, c.createdAt) desc
            """)
    List<Conversation> findOpenByContact(UUID channelAccountId, String contactHash);

    @Override
    @Query("""
            select c from Conversation c where c.channelAccountId = :channelAccountId and c.contactHash = :contactHash
               and c.debtorId is not null
             order by c.createdAt desc
            """)
    List<Conversation> findIdentifiedByContact(UUID channelAccountId, String contactHash);

    @Override
    @Query("""
            select c from Conversation c where c.caseId = :caseId
               and c.status = br.com.gestorcobranca.communication.domain.model.ConversationStatus.OPEN
            """)
    List<Conversation> findOpenByCaseId(UUID caseId);

    @Override
    @Query("""
            select c from Conversation c where c.debtorId = :debtorId and c.creditorId = :creditorId and c.caseId is null
               and c.status = br.com.gestorcobranca.communication.domain.model.ConversationStatus.OPEN
            """)
    List<Conversation> findOpenWithoutCase(UUID debtorId, UUID creditorId);

    @Override
    default Page<Conversation> search(ConversationSearchCriteria c, Pageable pageable) {
        Specification<Conversation> spec = Specification.unrestricted();
        if (c.allowedCreditorIds() != null) {
            spec = spec.and((root, query, cb) -> c.allowedCreditorIds().isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(c.allowedCreditorIds()));
        }
        spec = eq(spec, "creditorId", c.creditorId());
        spec = eq(spec, "portfolioId", c.portfolioId());
        spec = eq(spec, "debtorId", c.debtorId());
        spec = eq(spec, "caseId", c.caseId());
        spec = eq(spec, "channel", c.channel());
        spec = eq(spec, "status", c.status());
        spec = eq(spec, "ownerUserId", c.ownerUserId());
        spec = eq(spec, "ownerTeamId", c.ownerTeamId());
        spec = eq(spec, "requiredProfile", c.requiredProfile());
        if (c.ownerTypes() != null && !c.ownerTypes().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("ownerType").in(c.ownerTypes()));
        }
        if (c.awaitingReply() != null) {
            spec = spec.and((root, query, cb) -> c.awaitingReply() ? cb.isNotNull(root.get("awaitingSince"))
                    : cb.isNull(root.get("awaitingSince")));
        }
        return findAll(spec, pageable);
    }

    private static Specification<Conversation> eq(Specification<Conversation> spec, String field, Object value) {
        return value == null ? spec : spec.and((root, query, cb) -> cb.equal(root.get(field), value));
    }
}
