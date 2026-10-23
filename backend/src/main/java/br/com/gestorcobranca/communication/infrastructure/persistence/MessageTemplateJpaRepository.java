package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.MessageTemplate;
import br.com.gestorcobranca.communication.domain.model.TemplateStatus;
import br.com.gestorcobranca.communication.domain.repository.MessageTemplateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface MessageTemplateJpaRepository extends JpaRepository<MessageTemplate, UUID>,
        JpaSpecificationExecutor<MessageTemplate>, MessageTemplateRepository {

    @Override
    default Optional<MessageTemplate> findPublished(UUID creditorId, String code) {
        return creditorId == null ? findPublishedTenantWide(code) : findPublishedForCreditor(creditorId, code);
    }

    @Query("""
            select t from MessageTemplate t where t.creditorId is null and t.code = :code
               and t.status = br.com.gestorcobranca.communication.domain.model.TemplateStatus.PUBLISHED
            """)
    Optional<MessageTemplate> findPublishedTenantWide(String code);

    @Query("""
            select t from MessageTemplate t where t.creditorId = :creditorId and t.code = :code
               and t.status = br.com.gestorcobranca.communication.domain.model.TemplateStatus.PUBLISHED
            """)
    Optional<MessageTemplate> findPublishedForCreditor(UUID creditorId, String code);

    @Override
    default int maxVersion(UUID creditorId, String code) {
        Integer max = creditorId == null ? maxVersionTenantWide(code) : maxVersionForCreditor(creditorId, code);
        return max == null ? 0 : max;
    }

    @Query("select max(t.templateVersion) from MessageTemplate t where t.creditorId is null and t.code = :code")
    Integer maxVersionTenantWide(String code);

    @Query("select max(t.templateVersion) from MessageTemplate t where t.creditorId = :creditorId and t.code = :code")
    Integer maxVersionForCreditor(UUID creditorId, String code);

    @Override
    default Page<MessageTemplate> search(Collection<UUID> creditorIds, boolean includeTenantWide, UUID creditorId,
                                         String code, String channel, String status, Pageable pageable) {
        Specification<MessageTemplate> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, query, cb) -> {
                var inScope = creditorIds.isEmpty() ? cb.disjunction() : root.get("creditorId").in(creditorIds);
                return includeTenantWide ? cb.or(inScope, cb.isNull(root.get("creditorId"))) : inScope;
            });
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (code != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("code"), code));
        }
        if (channel != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("channel"), ChannelType.valueOf(channel)));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), TemplateStatus.valueOf(status)));
        }
        return findAll(spec, pageable);
    }
}
