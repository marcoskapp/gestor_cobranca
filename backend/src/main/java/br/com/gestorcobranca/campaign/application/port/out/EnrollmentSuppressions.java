package br.com.gestorcobranca.campaign.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Registro (para o painel) dos casos do segmento não inscritos por concorrência entre campanhas. */
public interface EnrollmentSuppressions {

    /** Uma linha por campanha e caso: atualiza a última ocorrência e a campanha que bloqueou. */
    void record(UUID campaignId, UUID caseId, UUID blockingCampaignId, Instant at);
}
