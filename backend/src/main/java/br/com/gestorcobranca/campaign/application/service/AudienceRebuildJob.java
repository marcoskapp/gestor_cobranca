package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.CampaignProperties;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseFilter;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Job {@code campaign.audience_rebuild}: reconstrói/reconcilia o read model de audiência a partir de
 * {@code CaseUseCases#search} (casos ativos do credor ou de todos), uma página por execução, encadeando
 * a próxima. A projeção nunca regride (versão do caso), então páginas concorrentes com eventos são seguras.
 */
@Component
class AudienceRebuildJob implements JobHandler<AudienceRebuildJob.Payload> {

    static final String TYPE = "campaign.audience_rebuild";
    static final Set<String> ACTIVE_STATUSES = Set.of("OPEN", "IN_NEGOTIATION", "PROMISE", "AGREEMENT", "DISPUTED",
            "SUSPENDED", "LEGAL");

    private final CaseUseCases cases;
    private final AudienceProjection projection;
    private final JobQueue jobQueue;
    private final CampaignProperties properties;

    AudienceRebuildJob(CaseUseCases cases, AudienceProjection projection, JobQueue jobQueue,
                       CampaignProperties properties) {
        this.cases = cases;
        this.projection = projection;
        this.jobQueue = jobQueue;
        this.properties = properties;
    }

    /** @param creditorId nulo = todos os credores */
    record Payload(UUID creditorId, int page) {
    }

    static String dedupKey(UUID creditorId, int page) {
        return "audience:" + (creditorId == null ? "all" : creditorId) + ":" + page;
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
        PageResponse<CaseView> page = cases.search(new CaseFilter(payload.creditorId(), null, null, ACTIVE_STATUSES, null,
                null, null, null), new PageQuery(payload.page(), properties.audienceBatch()));
        page.content().forEach(view -> projection.apply(view, "rebuild"));
        if (payload.page() + 1 < page.totalPages()) {
            jobQueue.enqueue(TYPE, new Payload(payload.creditorId(), payload.page() + 1), null,
                    dedupKey(payload.creditorId(), payload.page() + 1));
        }
    }
}
