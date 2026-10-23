package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases;
import br.com.gestorcobranca.campaign.application.port.out.CampaignOfferPort;
import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStatus;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.CampaignPurpose;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import br.com.gestorcobranca.campaign.domain.model.Segment;
import br.com.gestorcobranca.campaign.domain.repository.CadenceRepository;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class CampaignService implements CampaignUseCases {

    static final int DEFAULT_PRIORITY = 100;
    static final int DEFAULT_ATTRIBUTION_DAYS = 7;
    static final int DEFAULT_MAX_DEFERRAL_HOURS = 24;

    private final CampaignRepository campaigns;
    private final CadenceRepository cadences;
    private final CampaignAccess access;
    private final AccessScope accessScope;
    private final CampaignCodec codec;
    private final CampaignViews views;
    private final TemplateCheck templateCheck;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final ObjectProvider<CampaignOfferPort> offers;
    private final AuditTrail audit;
    private final Clock clock;

    CampaignService(CampaignRepository campaigns, CadenceRepository cadences, CampaignAccess access,
                    AccessScope accessScope, CampaignCodec codec, CampaignViews views, TemplateCheck templateCheck,
                    CreditorUseCases creditors, PortfolioUseCases portfolios, ObjectProvider<CampaignOfferPort> offers,
                    AuditTrail audit, Clock clock) {
        this.campaigns = campaigns;
        this.cadences = cadences;
        this.access = access;
        this.accessScope = accessScope;
        this.codec = codec;
        this.views = views;
        this.templateCheck = templateCheck;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.offers = offers;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public CampaignView create(CreateCampaignCommand command) {
        Guard.require(command != null && command.settings() != null, "campaign.required", "Dados da campanha obrigatórios");
        Guard.require(command.creditorId() != null, "campaign.creditor_required", "Informe o credor");
        accessScope.requireCreditor(command.creditorId());
        creditors.get(command.creditorId());
        if (command.portfolioId() != null) {
            Guard.require(portfolios.get(command.portfolioId()).creditorId().equals(command.creditorId()),
                    "campaign.portfolio_invalid", "Carteira de outro credor");
        }
        Guard.requireState(command.code() == null || !campaigns.existsByCode(command.code()), "campaign.code_duplicated",
                "Já existe campanha com este código");
        Campaign.Settings settings = settings(command.settings(), command.creditorId());
        Campaign campaign = campaigns.save(new Campaign(command.code(), command.creditorId(), command.portfolioId(),
                settings));
        audit.record(AuditEntry.of("campaign.created", "campaign", campaign.getId()).creditor(campaign.getCreditorId())
                .created("code", campaign.getCode()).created("segmentId", campaign.getSegmentId())
                .created("purpose", campaign.getPurpose()).created("priority", campaign.getPriority())
                .created("exclusivityGroup", campaign.getExclusivityGroup())
                .created("portfolioId", campaign.getPortfolioId()));
        return view(campaign);
    }

    @Override
    public CampaignView update(UUID campaignId, CampaignSettings input) {
        Guard.require(input != null, "campaign.required", "Dados da campanha obrigatórios");
        Campaign campaign = access.campaignForCommand(campaignId);
        Campaign.Settings settings = settings(input, campaign.getCreditorId());
        UUID segmentBefore = campaign.getSegmentId();
        int priorityBefore = campaign.getPriority();
        String groupBefore = campaign.getExclusivityGroup();
        campaign.update(settings);
        campaigns.save(campaign);
        audit.record(AuditEntry.of("campaign.updated", "campaign", campaignId).creditor(campaign.getCreditorId())
                .change("segmentId", segmentBefore, campaign.getSegmentId())
                .change("priority", priorityBefore, campaign.getPriority())
                .change("exclusivityGroup", groupBefore, campaign.getExclusivityGroup()));
        return view(campaign);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignView get(UUID campaignId) {
        return view(access.visibleCampaign(campaignId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CampaignView> search(CampaignFilter filter, PageQuery page) {
        CampaignFilter f = filter == null ? new CampaignFilter(null, null) : filter;
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(Set.of());
        CampaignStatus status = f.status() == null ? null : parse(CampaignStatus.class, f.status(), "campaign.status_invalid");
        return PageResponse.of(campaigns.search(allowed, f.creditorId(), status, page.toPageable(Sort.by("code"))),
                this::view);
    }

    @Override
    public CadenceView saveDraft(UUID campaignId, CadenceDefinition definition) {
        Guard.require(definition != null, "cadence.required", "Informe a régua");
        Campaign campaign = access.campaignForCommand(campaignId);
        Guard.requireState(campaign.getStatus() != CampaignStatus.FINISHED, "campaign.finished", "Campanha encerrada");
        definition.validate();
        String json = codec.cadence(definition);
        Optional<Cadence> draft = cadences.findByCampaignIdAndStatus(campaignId, CadenceStatus.DRAFT);
        Cadence cadence;
        if (draft.isPresent()) {
            cadence = draft.get();
            cadence.updateDefinition(json);
            cadence = cadences.save(cadence);
            audit.record(AuditEntry.of("cadence.draft_updated", "campaign_cadence", cadence.getId())
                    .creditor(campaign.getCreditorId()).meta("versionNumber", cadence.getVersionNumber())
                    .meta("steps", definition.steps().size()));
        } else {
            cadence = cadences.save(new Cadence(campaignId, cadences.maxVersion(campaignId) + 1, json));
            audit.record(AuditEntry.of("cadence.draft_created", "campaign_cadence", cadence.getId())
                    .creditor(campaign.getCreditorId()).created("versionNumber", cadence.getVersionNumber())
                    .created("steps", definition.steps().size()));
        }
        return views.cadence(cadence);
    }

    @Override
    public CadenceView publishDraft(UUID campaignId) {
        Campaign campaign = access.campaignForCommand(campaignId);
        Guard.requireState(campaign.getStatus() != CampaignStatus.FINISHED, "campaign.finished", "Campanha encerrada");
        Cadence draft = cadences.findByCampaignIdAndStatus(campaignId, CadenceStatus.DRAFT)
                .orElseThrow(() -> new ConflictException("cadence.no_draft", "Não há rascunho de régua para publicar"));
        CadenceDefinition definition = codec.cadence(draft.getDefinition()).validate();
        if (definition.hasAction(ActionType.OFFER) && offers.getIfAvailable() == null) {
            throw new ConflictException("cadence.offer_unavailable",
                    "Oferta/link de pagamento ainda não disponível: remova as etapas de oferta");
        }
        for (CadenceStep step : definition.steps()) {
            templateCheck.check(step.code(), step.action(), campaign.getCreditorId());
        }
        Instant now = Instant.now(clock);
        Optional<Cadence> published = cadences.findByCampaignIdAndStatus(campaignId, CadenceStatus.PUBLISHED);
        published.ifPresent(p -> {
            p.retire(now);
            cadences.saveAndFlush(p);
        });
        draft.publish(now, access.actor().id());
        Cadence saved = cadences.saveAndFlush(draft);
        audit.record(AuditEntry.of("cadence.published", "campaign_cadence", saved.getId()).creditor(campaign.getCreditorId())
                .meta("campaignId", campaignId).meta("versionNumber", saved.getVersionNumber())
                .meta("replacedVersion", published.map(Cadence::getVersionNumber).orElse(null))
                .meta("steps", definition.steps().size()));
        return views.cadence(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CadenceView> cadences(UUID campaignId) {
        access.visibleCampaign(campaignId);
        return cadences.findByCampaignIdOrderByVersionNumberDesc(campaignId).stream().map(views::cadence).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CadenceView cadence(UUID cadenceId) {
        Cadence cadence = cadences.findById(cadenceId)
                .orElseThrow(() -> br.com.gestorcobranca.shared.domain.ResourceNotFoundException.of("campaign_cadence",
                        cadenceId));
        try {
            access.visibleCampaign(cadence.getCampaignId());
        } catch (br.com.gestorcobranca.shared.domain.ResourceNotFoundException e) {
            throw br.com.gestorcobranca.shared.domain.ResourceNotFoundException.of("campaign_cadence", cadenceId);
        }
        return views.cadence(cadence);
    }

    private CampaignView view(Campaign c) {
        return views.campaign(c, cadences.findByCampaignIdAndStatus(c.getId(), CadenceStatus.PUBLISHED),
                cadences.findByCampaignIdAndStatus(c.getId(), CadenceStatus.DRAFT).isPresent());
    }

    private Campaign.Settings settings(CampaignSettings s, UUID creditorId) {
        Guard.require(s.segmentId() != null, "campaign.segment_required", "Informe o segmento");
        Segment segment = access.visibleSegment(s.segmentId());
        Guard.require(segment.getCreditorId() == null || segment.getCreditorId().equals(creditorId),
                "campaign.segment_creditor_mismatch", "Segmento de outro credor");
        Guard.require(segment.isActive(), "campaign.segment_inactive", "Segmento inativo");
        CampaignPurpose purpose = s.purpose() == null ? CampaignPurpose.EXTRAJUDICIAL_COLLECTION
                : parse(CampaignPurpose.class, s.purpose(), "campaign.purpose_invalid");
        String group = s.exclusivityGroup() == null || s.exclusivityGroup().isBlank() ? null
                : s.exclusivityGroup().trim().toUpperCase(Locale.ROOT);
        return new Campaign.Settings(s.name(), s.description(), s.segmentId(), purpose,
                s.priority() == null ? DEFAULT_PRIORITY : s.priority(), group, s.reenrollAfterDays(),
                s.attributionWindowDays() == null ? DEFAULT_ATTRIBUTION_DAYS : s.attributionWindowDays(),
                s.paymentPauseDays(), s.maxDeferralHours() == null ? DEFAULT_MAX_DEFERRAL_HOURS : s.maxDeferralHours(),
                s.exitWhenOutOfSegment() == null || s.exitWhenOutOfSegment());
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, "Valor inválido: " + value);
        }
    }
}
