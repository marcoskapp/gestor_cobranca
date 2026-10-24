package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.collection.application.port.in.QueueUseCases;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics.Counters;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.DialerEntry;
import br.com.gestorcobranca.telephony.domain.model.DialerEntryStatus;
import br.com.gestorcobranca.telephony.domain.model.DialerMode;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerEntryRepository;
import br.com.gestorcobranca.telephony.domain.service.DialerPacing;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
class DialerCampaignService implements DialerCampaignUseCases {

    private static final int FINISH_BATCH = 500;

    private final DialerCampaignRepository campaigns;
    private final DialerEntryRepository entries;
    private final AgentRepository agents;
    private final CallRepository calls;
    private final CallStatistics statistics;
    private final QueueUseCases queues;
    private final PortfolioUseCases portfolios;
    private final DialerTicks ticks;
    private final JobQueue jobQueue;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final TelephonyProperties properties;
    private final Clock clock;

    DialerCampaignService(DialerCampaignRepository campaigns, DialerEntryRepository entries, AgentRepository agents,
                          CallRepository calls, CallStatistics statistics, QueueUseCases queues,
                          PortfolioUseCases portfolios, DialerTicks ticks, JobQueue jobQueue, AccessScope accessScope,
                          AuditTrail auditTrail, BusinessEventLogger businessEvents, TelephonyProperties properties,
                          Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.agents = agents;
        this.calls = calls;
        this.statistics = statistics;
        this.queues = queues;
        this.portfolios = portfolios;
        this.ticks = ticks;
        this.jobQueue = jobQueue;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public CampaignView create(CreateCampaignCommand command) {
        Guard.require(command != null && command.queueId() != null, "dialer_campaign.queue_required",
                "Fila de origem obrigatória");
        QueueView queue = queues.get(command.queueId());
        UUID creditorId = command.creditorId();
        if (queue.creditorId() != null) {
            Guard.require(creditorId == null || creditorId.equals(queue.creditorId()), "dialer_campaign.creditor_mismatch",
                    "O credor da campanha deve ser o da fila");
            creditorId = queue.creditorId();
        }
        requireScope(creditorId);
        DialerCampaign.Settings settings = settings(command.settings());
        validatePortfolio(creditorId, settings.portfolioId());
        DialerCampaign campaign = new DialerCampaign(command.code(), creditorId, queue.id(), settings);
        if (campaigns.existsByCode(campaign.getCode())) {
            throw new ConflictException("dialer_campaign.code_taken", "Já existe campanha de discagem com este código");
        }
        campaigns.saveAndFlush(campaign);
        auditTrail.record(AuditEntry.of("dialer_campaign.created", "dialer_campaign", campaign.getId())
                .creditor(creditorId)
                .created("code", campaign.getCode())
                .created("queueId", campaign.getQueueId())
                .created("mode", campaign.getMode())
                .created("maxDialRatio", campaign.getMaxDialRatio().toPlainString())
                .created("maxAbandonRate", campaign.getMaxAbandonRate())
                .created("maxAttempts", campaign.getMaxAttempts())
                .created("retryIntervalMinutes", campaign.getRetryIntervalMinutes()));
        return CallViews.campaign(campaign);
    }

    @Override
    public CampaignView update(UUID campaignId, CampaignSettings input) {
        DialerCampaign campaign = forCommand(campaignId);
        DialerCampaign.Settings settings = settings(input);
        validatePortfolio(campaign.getCreditorId(), settings.portfolioId());
        String beforeMode = campaign.getMode().name();
        String beforeRatio = campaign.getMaxDialRatio().toPlainString();
        Percentage beforeAbandon = campaign.getMaxAbandonRate();
        int beforeAttempts = campaign.getMaxAttempts();
        int beforeRetry = campaign.getRetryIntervalMinutes();
        campaign.configure(settings);
        campaigns.save(campaign);
        auditTrail.record(AuditEntry.of("dialer_campaign.updated", "dialer_campaign", campaign.getId())
                .creditor(campaign.getCreditorId())
                .change("mode", beforeMode, campaign.getMode().name())
                .change("maxDialRatio", beforeRatio, campaign.getMaxDialRatio().toPlainString())
                .change("maxAbandonRate", beforeAbandon, campaign.getMaxAbandonRate())
                .change("maxAttempts", beforeAttempts, campaign.getMaxAttempts())
                .change("retryIntervalMinutes", beforeRetry, campaign.getRetryIntervalMinutes()));
        return CallViews.campaign(campaign);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignView get(UUID campaignId) {
        return CallViews.campaign(visible(campaignId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CampaignView> list() {
        return campaigns.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> c.getCreditorId() == null ? accessScope.hasAllCreditors()
                        : accessScope.canAccessCreditor(c.getCreditorId()))
                .map(CallViews::campaign).toList();
    }

    @Override
    public CampaignView activate(UUID campaignId) {
        DialerCampaign campaign = forCommand(campaignId);
        String before = campaign.getStatus().name();
        boolean resumed = campaign.getActivatedAt() != null;
        campaign.activate(Instant.now(clock));
        campaigns.save(campaign);
        DialerLoadJob.start(jobQueue, campaign.getId());
        ticks.requestNow(campaign.getId());
        audit(campaign, "dialer_campaign.activated", before);
        businessEvents.log(event(BusinessEventTypes.DIALER_CAMPAIGN_ACTIVATED, campaign).attr("resumed", resumed));
        return CallViews.campaign(campaign);
    }

    @Override
    public CampaignView pause(UUID campaignId) {
        DialerCampaign campaign = forCommand(campaignId);
        String before = campaign.getStatus().name();
        campaign.pause(Instant.now(clock));
        campaigns.save(campaign);
        audit(campaign, "dialer_campaign.paused", before);
        businessEvents.log(event(BusinessEventTypes.DIALER_CAMPAIGN_PAUSED, campaign));
        return CallViews.campaign(campaign);
    }

    @Override
    public CampaignView finish(UUID campaignId) {
        DialerCampaign campaign = forCommand(campaignId);
        String before = campaign.getStatus().name();
        Instant now = Instant.now(clock);
        campaign.finish(now);
        campaigns.save(campaign);
        int excluded = 0;
        UUID after = null;
        List<UUID> pending;
        do {
            pending = entries.findPendingIds(campaign.getId(), after, FINISH_BATCH);
            for (DialerEntry entry : entries.findAllByIdIn(pending)) {
                entry.exclude("CAMPAIGN_FINISHED", now);
                entries.save(entry);
                excluded++;
            }
            after = pending.isEmpty() ? after : pending.getLast();
        } while (pending.size() == FINISH_BATCH);
        audit(campaign, "dialer_campaign.finished", before);
        businessEvents.log(event(BusinessEventTypes.DIALER_CAMPAIGN_FINISHED, campaign)
                .attr("excludedEntries", excluded)
                .durationMs(campaign.getActivatedAt() == null ? 0
                        : java.time.Duration.between(campaign.getActivatedAt(), now).toMillis()));
        return CallViews.campaign(campaign);
    }

    @Override
    public CampaignView reload(UUID campaignId) {
        DialerCampaign campaign = forCommand(campaignId);
        Guard.requireState(campaign.getStatus() == br.com.gestorcobranca.telephony.domain.model.DialerCampaignStatus.ACTIVE
                        || campaign.getStatus() == br.com.gestorcobranca.telephony.domain.model.DialerCampaignStatus.PAUSED,
                "dialer_campaign.not_active", "Somente campanhas ativas ou pausadas recarregam a lista");
        DialerLoadJob.start(jobQueue, campaign.getId());
        auditTrail.record(AuditEntry.of("dialer_campaign.reload_requested", "dialer_campaign", campaign.getId())
                .creditor(campaign.getCreditorId()));
        return CallViews.campaign(campaign);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EntryView> entries(UUID campaignId, String status, PageQuery page) {
        DialerCampaign campaign = visible(campaignId);
        String filter = TelephonyEnums.parseOptional(DialerEntryStatus.class, status, "dialer_entry.status_invalid",
                "Situação") == null ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        return PageResponse.of(entries.findByCampaign(campaign.getId(), filter,
                page.toPageable(Sort.by("priority"))), CallViews::entry);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignStats stats(UUID campaignId) {
        DialerCampaign campaign = visible(campaignId);
        Instant now = Instant.now(clock);
        Instant since = ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).toLocalDate()
                .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Counters c = statistics.campaignCounters(campaign.getId(), since);
        long available = agents.countAvailableInCampaign(campaign.getId());
        long inProgress = calls.countUnconnectedActiveByCampaign(campaign.getId());
        DialerPacing.Decision pacing = DialerPacing.decide(new DialerPacing.Input(campaign.getMode(), (int) available,
                (int) inProgress, campaign.getMaxDialRatio(), campaign.getMaxAbandonRate(), c.live(), c.abandoned(),
                c.dialed(), properties.dialer().maxPerTick()));
        return new CampaignStats(campaign.getId(), since, c.dialed(), c.answered(), c.live(), c.connected(), c.abandoned(),
                c.machine(), percent(c.answered(), c.dialed()), DialerPacing.abandonRatePercent(c.live(), c.abandoned()),
                campaign.getMaxAbandonRate().value(), average(c.talkSeconds(), c.connected()),
                average(c.ringSeconds(), c.ended()), average(c.wrapUpSeconds(), c.tabulated()), available, inProgress,
                pacing.ratio(), pacing.reason(), count(campaign, DialerEntryStatus.PENDING),
                count(campaign, DialerEntryStatus.IN_PROGRESS), count(campaign, DialerEntryStatus.COMPLETED),
                count(campaign, DialerEntryStatus.EXHAUSTED), count(campaign, DialerEntryStatus.EXCLUDED));
    }

    private long count(DialerCampaign campaign, DialerEntryStatus status) {
        return entries.countByCampaignAndStatus(campaign.getId(), status.name());
    }

    private static BigDecimal percent(long part, long total) {
        return total == 0 ? BigDecimal.ZERO.setScale(2) : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_EVEN);
    }

    private static Double average(long sum, long count) {
        return count == 0 ? null : (double) sum / count;
    }

    private DialerCampaign.Settings settings(CampaignSettings s) {
        Guard.require(s != null, "dialer_campaign.settings_required", "Parâmetros obrigatórios");
        DialerMode mode = TelephonyEnums.parse(DialerMode.class, s.mode(), "dialer_campaign.mode_invalid", "Modo");
        Percentage abandon;
        try {
            abandon = s.maxAbandonRate() == null ? null : Percentage.of(s.maxAbandonRate());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("dialer_campaign.abandon_invalid", "Abandono máximo inválido");
        }
        return new DialerCampaign.Settings(s.name(), s.portfolioId(), s.includeAssigned(), mode, s.maxDialRatio(), abandon,
                s.maxAttempts(), s.retryIntervalMinutes(), s.ringTimeoutSeconds(), s.windowStart(), s.windowEnd());
    }

    private void validatePortfolio(UUID creditorId, UUID portfolioId) {
        if (portfolioId == null) {
            return;
        }
        UUID owner = portfolios.get(portfolioId).creditorId();
        Guard.require(creditorId == null || Objects.equals(owner, creditorId), "dialer_campaign.portfolio_mismatch",
                "A carteira não pertence ao credor da campanha");
        accessScope.requireCreditor(owner);
    }

    private DialerCampaign visible(UUID campaignId) {
        DialerCampaign campaign = campaigns.findById(campaignId)
                .orElseThrow(() -> ResourceNotFoundException.of("dialer_campaign", campaignId));
        if (campaign.getCreditorId() == null) {
            if (!accessScope.hasAllCreditors()) {
                throw ResourceNotFoundException.of("dialer_campaign", campaignId);
            }
        } else {
            accessScope.requireVisible("dialer_campaign", campaign.getId(), campaign.getCreditorId());
        }
        return campaign;
    }

    private DialerCampaign forCommand(UUID campaignId) {
        visible(campaignId);
        DialerCampaign campaign = campaigns.findByIdForUpdate(campaignId).orElseThrow();
        requireScope(campaign.getCreditorId());
        return campaign;
    }

    private void requireScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("dialer_campaign");
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    private void audit(DialerCampaign campaign, String action, String before) {
        auditTrail.record(AuditEntry.of(action, "dialer_campaign", campaign.getId())
                .creditor(campaign.getCreditorId()).change("status", before, campaign.getStatus().name()));
    }

    private BusinessEvent event(String type, DialerCampaign campaign) {
        return BusinessEvent.of(type).creditor(campaign.getCreditorId()).portfolio(campaign.getPortfolioId())
                .reference("dialer_campaign", campaign.getId()).channel("PHONE")
                .attr("campaignCode", campaign.getCode()).attr("mode", campaign.getMode())
                .attr("maxDialRatio", campaign.getMaxDialRatio()).attr("maxAbandonRate", campaign.getMaxAbandonRate().value())
                .attr("maxAttempts", campaign.getMaxAttempts()).attr("queueId", campaign.getQueueId());
    }
}
