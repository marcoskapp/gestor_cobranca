package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.CampaignProperties;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Job {@code campaign.finish}: encerra em lotes (cursor por id) as inscrições de uma campanha encerrada. */
@Component
class FinishCampaignJob implements JobHandler<FinishCampaignJob.Payload> {

    static final String TYPE = "campaign.finish";

    private final CampaignRepository campaigns;
    private final CampaignMaintenance maintenance;
    private final CampaignProperties properties;

    FinishCampaignJob(CampaignRepository campaigns, CampaignMaintenance maintenance, CampaignProperties properties) {
        this.campaigns = campaigns;
        this.maintenance = maintenance;
        this.properties = properties;
    }

    record Payload(UUID campaignId, UUID afterEnrollmentId) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Campaign campaign = campaigns.findById(payload.campaignId()).orElse(null);
        if (campaign == null || campaign.getStatus() != CampaignStatus.FINISHED) {
            return;
        }
        maintenance.finishEnrollments(campaign, payload.afterEnrollmentId(), properties.maintenanceBatch());
    }
}
