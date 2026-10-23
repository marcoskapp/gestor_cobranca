package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaignStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DialerCampaignRepository {

    DialerCampaign save(DialerCampaign campaign);

    DialerCampaign saveAndFlush(DialerCampaign campaign);

    Optional<DialerCampaign> findById(UUID id);

    /** Com lock pessimista: um ciclo do discador por campanha de cada vez. */
    Optional<DialerCampaign> findByIdForUpdate(UUID id);

    boolean existsByCode(String code);

    List<DialerCampaign> findAllByOrderByCreatedAtDesc();

    List<DialerCampaign> findAllByStatus(DialerCampaignStatus status);
}
