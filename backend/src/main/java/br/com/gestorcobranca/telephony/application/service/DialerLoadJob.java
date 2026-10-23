package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseFilter;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaignStatus;
import br.com.gestorcobranca.telephony.domain.model.DialerEntry;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerEntryRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Montagem da lista da campanha a partir da fila do collection ({@code telephony.dialer_load}),
 * uma página por job: casos elegíveis em {@code OPEN}/{@code IN_NEGOTIATION} da fila (e do
 * credor/carteira da campanha), aguardando na fila ({@code ownerType = QUEUE}) salvo
 * {@code includeAssigned}. Casos já presentes na lista não são repetidos (único por campanha e
 * caso); a ordem de prioridade segue a ordem da consulta.
 */
@Component
class DialerLoadJob implements JobHandler<DialerLoadJob.Payload> {

    static final String TYPE = "telephony.dialer_load";

    private final DialerCampaignRepository campaigns;
    private final DialerEntryRepository entries;
    private final CaseUseCases cases;
    private final DialerTicks ticks;
    private final JobQueue jobQueue;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    DialerLoadJob(DialerCampaignRepository campaigns, DialerEntryRepository entries, CaseUseCases cases,
                  DialerTicks ticks, JobQueue jobQueue, TelephonyMetrics metrics, TelephonyProperties properties,
                  Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.cases = cases;
        this.ticks = ticks;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param page página da consulta à fila; {@code loaded} itens já considerados nesta carga */
    record Payload(UUID campaignId, int page, int loaded) {
    }

    static void start(JobQueue jobQueue, UUID campaignId) {
        jobQueue.enqueue(TYPE, new Payload(campaignId, 0, 0), null, "load:" + campaignId);
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
        Optional<DialerCampaign> found = campaigns.findByIdForUpdate(payload.campaignId());
        if (found.isEmpty() || found.get().getStatus() == DialerCampaignStatus.FINISHED
                || found.get().getStatus() == DialerCampaignStatus.DRAFT) {
            return;
        }
        DialerCampaign campaign = found.get();
        int size = properties.dialer().loadPageSize();
        PageResponse<CaseView> page = cases.search(new CaseFilter(campaign.getCreditorId(), campaign.getPortfolioId(),
                null, Set.of("OPEN", "IN_NEGOTIATION"), campaign.getQueueId(), null,
                campaign.isIncludeAssigned() ? null : "QUEUE", Boolean.TRUE), new PageQuery(payload.page(), size));
        Instant now = Instant.now(clock);
        List<CaseView> content = page.content();
        Set<UUID> existing = entries.findCaseIdsInCampaign(campaign.getId(), content.stream().map(CaseView::id).toList());
        int priority = entries.maxPriority(campaign.getId());
        int added = 0;
        for (CaseView kase : content) {
            if (existing.contains(kase.id()) || !campaign.getQueueId().equals(kase.queueId())) {
                continue;
            }
            entries.save(new DialerEntry(campaign.getId(), kase.id(), kase.debtorId(), kase.creditorId(),
                    kase.portfolioId(), ++priority, now));
            added++;
        }
        campaign.loaded(added, now);
        campaigns.save(campaign);
        if (added > 0) {
            metrics.entry("loaded");
            if (campaign.isActive()) {
                ticks.requestNow(campaign.getId());
            }
        }
        int considered = payload.loaded() + content.size();
        if (payload.page() + 1 < page.totalPages() && considered < properties.dialer().maxEntries()) {
            jobQueue.enqueue(TYPE, new Payload(campaign.getId(), payload.page() + 1, considered), null,
                    "load:" + campaign.getId() + ":" + (payload.page() + 1));
        }
    }
}
