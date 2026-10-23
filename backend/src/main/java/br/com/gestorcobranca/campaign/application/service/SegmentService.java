package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases;
import br.com.gestorcobranca.campaign.application.port.out.AudienceStore;
import br.com.gestorcobranca.campaign.application.port.out.AudienceStore.Selection;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import br.com.gestorcobranca.campaign.domain.model.Segment;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.campaign.domain.repository.SegmentRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class SegmentService implements SegmentUseCases {

    private final SegmentRepository segments;
    private final CampaignRepository campaigns;
    private final AudienceStore audience;
    private final CampaignAccess access;
    private final AccessScope accessScope;
    private final CampaignCodec codec;
    private final CampaignViews views;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final AuditTrail audit;
    private final JobQueue jobQueue;
    private final CampaignMetrics metrics;
    private final Clock clock;

    SegmentService(SegmentRepository segments, CampaignRepository campaigns, AudienceStore audience,
                   CampaignAccess access, AccessScope accessScope, CampaignCodec codec, CampaignViews views,
                   CreditorUseCases creditors, PortfolioUseCases portfolios, AuditTrail audit, JobQueue jobQueue,
                   CampaignMetrics metrics, Clock clock) {
        this.segments = segments;
        this.campaigns = campaigns;
        this.audience = audience;
        this.access = access;
        this.accessScope = accessScope;
        this.codec = codec;
        this.views = views;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.audit = audit;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public SegmentView create(CreateSegmentCommand command) {
        Guard.require(command != null, "segment.required", "Dados do segmento obrigatórios");
        access.requireCreditorOrAll(command.creditorId(), "segment");
        if (command.creditorId() != null) {
            creditors.get(command.creditorId());
        }
        SegmentCriteria criteria = criteria(command.criteria(), command.creditorId());
        Guard.requireState(command.code() == null || !segments.existsByCode(command.code()), "segment.code_duplicated",
                "Já existe segmento com este código");
        Segment segment = segments.save(new Segment(command.creditorId(), command.code(), command.name(),
                command.description(), codec.criteria(criteria)));
        audit.record(AuditEntry.of("segment.created", "segment", segment.getId()).creditor(segment.getCreditorId())
                .created("code", segment.getCode()).created("name", segment.getName())
                .created("criteria", segment.getCriteria()));
        return views.segment(segment);
    }

    @Override
    public SegmentView update(UUID segmentId, UpdateSegmentCommand command) {
        Guard.require(command != null, "segment.required", "Dados do segmento obrigatórios");
        Segment segment = access.segmentForCommand(segmentId);
        SegmentCriteria criteria = criteria(command.criteria(), segment.getCreditorId());
        String before = segment.getCriteria();
        String beforeName = segment.getName();
        segment.update(command.name(), command.description(), codec.criteria(criteria));
        segments.save(segment);
        audit.record(AuditEntry.of("segment.updated", "segment", segment.getId()).creditor(segment.getCreditorId())
                .change("name", beforeName, segment.getName()).change("criteria", before, segment.getCriteria()));
        return views.segment(segment);
    }

    @Override
    public SegmentView changeActive(UUID segmentId, boolean active) {
        Segment segment = access.segmentForCommand(segmentId);
        if (!active && campaigns.existsBySegmentIdAndStatusIn(segmentId, EnumSet.of(CampaignStatus.ACTIVE))) {
            throw new ConflictException("segment.in_use", "Segmento usado por campanha ativa");
        }
        boolean before = segment.isActive();
        segment.changeActive(active);
        segments.save(segment);
        audit.record(AuditEntry.of(active ? "segment.activated" : "segment.deactivated", "segment", segmentId)
                .creditor(segment.getCreditorId()).change("active", before, active));
        return views.segment(segment);
    }

    @Override
    @Transactional(readOnly = true)
    public SegmentView get(UUID segmentId) {
        return views.segment(access.visibleSegment(segmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SegmentView> search(SegmentFilter filter, PageQuery page) {
        SegmentFilter f = filter == null ? new SegmentFilter(null, null) : filter;
        boolean all = accessScope.hasAllCreditors();
        Set<UUID> allowed = all ? null : accessScope.restrictCreditors(Set.of());
        return PageResponse.of(segments.search(allowed, all, f.creditorId(), f.active(),
                page.toPageable(Sort.by("code"))), views::segment);
    }

    @Override
    @Transactional(readOnly = true)
    public AudienceCount count(UUID segmentId, UUID creditorId, UUID portfolioId) {
        Segment segment = access.visibleSegment(segmentId);
        UUID creditor = segment.getCreditorId() != null ? segment.getCreditorId() : creditorId;
        Guard.require(creditorId == null || segment.getCreditorId() == null || creditorId.equals(segment.getCreditorId()),
                "segment.creditor_mismatch", "Segmento de outro credor");
        return evaluate(codec.criteria(segment.getCriteria()), creditor, portfolioId);
    }

    @Override
    @Transactional(readOnly = true)
    public AudienceCount preview(PreviewCommand command) {
        Guard.require(command != null, "segment.required", "Informe os filtros");
        SegmentCriteria criteria = criteria(command.criteria(), command.creditorId());
        return evaluate(criteria, command.creditorId(), command.portfolioId());
    }

    @Override
    public void rebuildAudience(UUID creditorId) {
        access.requireCreditorOrAll(creditorId, "campaign_audience");
        jobQueue.enqueue(AudienceRebuildJob.TYPE, new AudienceRebuildJob.Payload(creditorId, 0), null,
                AudienceRebuildJob.dedupKey(creditorId, 0));
        audit.record(AuditEntry.of("campaign.audience_rebuild_requested", "campaign_audience",
                creditorId == null ? UUID.nameUUIDFromBytes("all".getBytes()) : creditorId).creditor(creditorId));
    }

    /** Contagem no escopo: credor informado (no escopo) ou, sem credor, todos — exige escopo total. */
    private AudienceCount evaluate(SegmentCriteria criteria, UUID creditorId, UUID portfolioId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("segment");
        } else {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        if (portfolioId != null) {
            var portfolio = portfolios.get(portfolioId);
            Guard.require(creditorId == null || portfolio.creditorId().equals(creditorId), "segment.portfolio_invalid",
                    "Carteira de outro credor");
        }
        Instant now = Instant.now(clock);
        AudienceCount count = audience.count(new Selection(criteria, creditorId, portfolioId,
                now.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(), now));
        metrics.segmentEvaluation("count", Duration.between(now, Instant.now(clock)));
        return count;
    }

    /** Valida os filtros; carteiras precisam ser do credor do segmento. */
    private SegmentCriteria criteria(SegmentCriteria input, UUID creditorId) {
        SegmentCriteria criteria = (input == null ? SegmentCriteria.any() : input).validate();
        for (UUID portfolioId : criteria.portfolioIds()) {
            var portfolio = portfolios.get(portfolioId);
            Guard.require(creditorId == null || portfolio.creditorId().equals(creditorId), "segment.portfolio_invalid",
                    "Carteira de outro credor");
        }
        return criteria;
    }
}
