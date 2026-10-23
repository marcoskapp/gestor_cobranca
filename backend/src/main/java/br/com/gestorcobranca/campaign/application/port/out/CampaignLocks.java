package br.com.gestorcobranca.campaign.application.port.out;

import java.util.UUID;

/** Locks consultivos de transação do módulo. */
public interface CampaignLocks {

    /** Serializa inscrições do mesmo devedor (controle de concorrência entre campanhas). */
    void lockDebtor(UUID debtorId);
}
