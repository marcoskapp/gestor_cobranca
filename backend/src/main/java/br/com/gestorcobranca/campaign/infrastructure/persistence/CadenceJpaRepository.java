package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.repository.CadenceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

interface CadenceJpaRepository extends JpaRepository<Cadence, UUID>, CadenceRepository {

    @Override
    default int maxVersion(UUID campaignId) {
        Integer max = maxVersionNumber(campaignId);
        return max == null ? 0 : max;
    }

    @Query("select max(c.versionNumber) from Cadence c where c.campaignId = :campaignId")
    Integer maxVersionNumber(UUID campaignId);
}
