package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface CampaignJpaRepository extends JpaRepository<Campaign, UUID>, JpaSpecificationExecutor<Campaign>,
        CampaignRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(UUID id);

    @Override
    default Page<Campaign> search(Collection<UUID> creditorIds, UUID creditorId, CampaignStatus status, Pageable pageable) {
        Specification<Campaign> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, query, cb) -> creditorIds.isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return findAll(spec, pageable);
    }
}
