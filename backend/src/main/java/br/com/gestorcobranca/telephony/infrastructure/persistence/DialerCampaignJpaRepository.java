package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface DialerCampaignJpaRepository extends JpaRepository<DialerCampaign, UUID>, DialerCampaignRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DialerCampaign c where c.id = :id")
    Optional<DialerCampaign> findByIdForUpdate(UUID id);
}
