package br.com.gestorcobranca.campaign.application.port.out;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.CampaignPanel;

import java.time.Instant;
import java.util.UUID;

/** Agregações do painel de execução (somente leitura, tabelas do próprio módulo). */
public interface CampaignPanelQueries {

    CampaignPanel panel(UUID campaignId, Instant now);
}
