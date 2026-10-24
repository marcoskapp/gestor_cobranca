package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases;
import br.com.gestorcobranca.portal.application.port.out.PortalFunnelReader;
import br.com.gestorcobranca.portal.application.port.out.PortalRouteDirectory;
import br.com.gestorcobranca.portal.domain.model.KnowledgeChallenge;
import br.com.gestorcobranca.portal.domain.model.PortalChannel;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.model.SessionStatus;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.portal.domain.service.BrandingRules;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class PortalConfigurationService implements PortalConfigurationUseCases {

    static final String RESOURCE = "portal_configuration";

    private final PortalConfigurationRepository configurations;
    private final PortalSessionRepository sessions;
    private final PortalRouteDirectory routes;
    private final PortalFunnelReader funnel;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final FileStorage fileStorage;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final PortalProperties properties;
    private final PortalMetrics metrics;
    private final Clock clock;

    PortalConfigurationService(PortalConfigurationRepository configurations, PortalSessionRepository sessions,
                         PortalRouteDirectory routes, PortalFunnelReader funnel, CreditorUseCases creditors,
                         PortfolioUseCases portfolios, FileStorage fileStorage, AccessScope accessScope,
                         CurrentActor currentActor, AuditTrail auditTrail, AccessLogger accessLogger,
                         BusinessEventLogger businessEvents, PortalProperties properties, PortalMetrics metrics,
                         Clock clock) {
        this.configurations = configurations;
        this.sessions = sessions;
        this.routes = routes;
        this.funnel = funnel;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.fileStorage = fileStorage;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigurationView> list() {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        return configurations.findAllByOrderByDisplayNameAsc().stream()
                .filter(c -> accessScope.canAccessCreditor(c.getCreditorId())).map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ConfigurationView get(UUID configurationId) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        return view(loadVisible(configurationId));
    }

    @Override
    public ConfigurationView create(ConfigurationCommand command) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        Guard.require(command != null && command.creditorId() != null, "portal.creditor_required", "Credor obrigatório");
        accessScope.requireCreditor(command.creditorId());
        creditors.requireActive(command.creditorId());
        if (configurations.findByCreditorId(command.creditorId()).isPresent()) {
            throw new ConflictException("portal.configuration_exists", "O credor já tem portal configurado");
        }
        PortalConfiguration.Settings settings = settings(command);
        requirePortfolios(command.creditorId(), settings.portfolioIds());
        requireSlugAvailable(settings.slug(), null);
        PortalConfiguration c = configurations.saveAndFlush(new PortalConfiguration(command.creditorId(), settings));
        routes.upsert(c.getSlug(), c.getTenantId(), c.getId(), c.isEnabled());
        auditTrail.record(AuditEntry.of("portal.configuration_created", RESOURCE, c.getId()).creditor(c.getCreditorId())
                .created("slug", c.getSlug()).created("enabled", c.isEnabled()).created("portfolios", c.getPortfolioIds())
                .created("otpChannels", c.getOtpChannels()).created("knowledgeChallenge", c.getKnowledgeChallenge())
                .created("privacyNoticeVersion", c.getPrivacyNoticeVersion()));
        changed(c, "CREATED", true);
        return view(c);
    }

    @Override
    public ConfigurationView update(UUID configurationId, ConfigurationCommand command) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        PortalConfiguration c = loadVisible(configurationId);
        accessScope.requireCreditor(c.getCreditorId());
        Guard.require(command != null, "portal.settings_required", "Configuração obrigatória");
        Guard.require(command.creditorId() == null || command.creditorId().equals(c.getCreditorId()),
                "portal.creditor_immutable", "O credor do portal não muda");
        PortalConfiguration.Settings settings = settings(command);
        requirePortfolios(c.getCreditorId(), settings.portfolioIds());
        requireSlugAvailable(settings.slug(), c.getId());
        Map<String, Object> before = snapshot(c);
        boolean noticeChanged = c.apply(settings);
        c = configurations.saveAndFlush(c);
        routes.upsert(c.getSlug(), c.getTenantId(), c.getId(), c.isEnabled());
        AuditEntry audit = AuditEntry.of("portal.configuration_updated", RESOURCE, c.getId()).creditor(c.getCreditorId());
        Map<String, Object> after = snapshot(c);
        before.forEach((k, v) -> audit.change(k, v, after.get(k)));
        auditTrail.record(audit);
        changed(c, "UPDATED", noticeChanged);
        return view(c);
    }

    @Override
    public ConfigurationView uploadLogo(UUID configurationId, LogoUpload upload) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        PortalConfiguration c = loadVisible(configurationId);
        accessScope.requireCreditor(c.getCreditorId());
        Guard.require(upload != null && upload.content() != null, "portal.logo_required", "Envie o arquivo do logotipo");
        byte[] bytes = readLimited(upload.content(), BrandingRules.MAX_LOGO_BYTES);
        String type = BrandingRules.detectImageType(bytes).orElseThrow(() -> new BusinessRuleException(
                "portal.logo_type_invalid", "Logotipo em PNG, JPEG ou WebP"));
        StoredFile stored = fileStorage.store(FileCategory.PORTAL_BRANDING, "logo", type,
                new ByteArrayInputStream(bytes), bytes.length);
        String previous = c.getLogoFileKey();
        c.changeLogo(stored.key(), type, stored.sha256());
        c = configurations.save(c);
        cleanupAfterCompletion(stored.key(), previous);
        auditTrail.record(AuditEntry.of("portal.logo_changed", RESOURCE, c.getId()).creditor(c.getCreditorId())
                .change("logoSha256", null, stored.sha256()).meta("contentType", type).meta("size", bytes.length));
        return view(c);
    }

    @Override
    public ConfigurationView removeLogo(UUID configurationId) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        PortalConfiguration c = loadVisible(configurationId);
        accessScope.requireCreditor(c.getCreditorId());
        String previousSha = c.getLogoSha256();
        String previous = c.removeLogo();
        c = configurations.save(c);
        cleanupAfterCompletion(null, previous);
        auditTrail.record(AuditEntry.of("portal.logo_removed", RESOURCE, c.getId()).creditor(c.getCreditorId())
                .change("logoSha256", previousSha, null));
        return view(c);
    }

    @Override
    @Transactional(readOnly = true)
    public PortalAccessUseCases.LogoContent logo(UUID configurationId) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        PortalConfiguration c = loadVisible(configurationId);
        if (c.getLogoFileKey() == null) {
            throw new ResourceNotFoundException("portal_logo", configurationId);
        }
        return new PortalAccessUseCases.LogoContent(c.getLogoContentType(), c.getLogoSha256(),
                fileStorage.open(c.getLogoFileKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public FunnelView funnel(UUID configurationId, LocalDate from, LocalDate to) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        PortalConfiguration c = loadVisible(configurationId);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        Guard.require(!start.isAfter(end), "portal.period_invalid", "Período inválido");
        Guard.require(!start.isBefore(end.minusDays(366)), "portal.period_too_long", "Período de até 1 ano");
        Instant fromInstant = ZonedDateTime.of(start.atStartOfDay(), TimeConfig.BUSINESS_ZONE).toInstant();
        Instant untilInstant = ZonedDateTime.of(end.plusDays(1).atStartOfDay(), TimeConfig.BUSINESS_ZONE).toInstant();
        PortalFunnelReader.Counts counts = funnel.counts(c.getId(), fromInstant, untilInstant);
        return new FunnelView(c.getId(), start, end, counts.accesses(), counts.simulations(), counts.acceptances(),
                counts.pixIssued(), counts.paid(), counts.serviceRequests());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SessionSummary> sessions(UUID configurationId, UUID debtorId, PageQuery page) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        PortalConfiguration c = loadVisible(configurationId);
        return PageResponse.of(sessions.search(c.getId(), debtorId,
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))), PortalConfigurationService::session);
    }

    @Override
    public SessionSummary revokeSession(UUID sessionId, String reason) {
        accessScope.requirePermission(Permission.PORTAL_MANAGE);
        PortalSession s = sessions.findById(sessionId).orElseThrow(() -> new ResourceNotFoundException("portal_session", sessionId));
        accessScope.requireVisible("portal_session", sessionId, s.getCreditorId());
        accessScope.requireCreditor(s.getCreditorId());
        String why = reason == null || reason.isBlank() ? "management" : reason.strip();
        Guard.require(why.length() <= 300, "portal.reason_too_long", "Motivo até 300 caracteres");
        if (s.end(SessionStatus.REVOKED, "management", Instant.now(clock))) {
            sessions.save(s);
            accessLogger.log(AccessEvent.of(AccessEventType.PORTAL_SESSION_REVOKED, currentActor.get(),
                    Map.of("sessionId", sessionId.toString(), "debtorId", s.getDebtorId().toString())));
            auditTrail.record(AuditEntry.of("portal.session_revoked", "portal_session", sessionId)
                    .creditor(s.getCreditorId()).change("status", SessionStatus.ACTIVE, SessionStatus.REVOKED).reason(why));
            metrics.session("REVOKED");
        }
        return session(s);
    }

    // ------------------------------------------------------------------ apoio

    private PortalConfiguration loadVisible(UUID id) {
        PortalConfiguration c = configurations.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, c.getCreditorId());
        return c;
    }

    private PortalConfiguration.Settings settings(ConfigurationCommand c) {
        Set<PortalChannel> channels = EnumSet.noneOf(PortalChannel.class);
        if (c.otpChannels() != null) {
            c.otpChannels().forEach(ch -> channels.add(PortalAccessService.channel(ch)));
        }
        KnowledgeChallenge knowledge = c.knowledgeChallenge() == null || c.knowledgeChallenge().isBlank()
                ? KnowledgeChallenge.NONE
                : PortalSelfServiceService.parse(KnowledgeChallenge.class, c.knowledgeChallenge(), "portal.knowledge_invalid");
        return new PortalConfiguration.Settings(c.slug(), c.enabled(), c.displayName(), c.primaryColor(), c.accentColor(),
                c.portfolioIds() == null ? Set.of() : Set.copyOf(c.portfolioIds()), channels, knowledge,
                c.linkValidityHours() <= 0 ? 48 : c.linkValidityHours(), c.privacyNoticeText(), c.supportText());
    }

    private void requirePortfolios(UUID creditorId, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<PortfolioView> found = portfolios.getAll(ids);
        Guard.require(found.size() == ids.size() && found.stream().allMatch(p -> p.creditorId().equals(creditorId)),
                "portal.portfolio_mismatch", "Todas as carteiras habilitadas precisam ser do credor do portal");
    }

    private void requireSlugAvailable(String slug, UUID self) {
        if (routes.slugTaken(BrandingRules.slug(slug), self)) {
            throw new ConflictException("portal.slug_taken", "Endereço já usado por outro portal");
        }
    }

    private void changed(PortalConfiguration c, String operation, boolean noticeChanged) {
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_CONFIGURATION_CHANGED).creditor(c.getCreditorId())
                .reference(RESOURCE, c.getId()).attr("operation", operation).attr("enabled", c.isEnabled())
                .attr("portfolios", c.getPortfolioIds().size()).attr("knowledgeChallenge", c.getKnowledgeChallenge())
                .attr("privacyNoticeVersion", c.getPrivacyNoticeVersion()).attr("noticeChanged", noticeChanged));
    }

    private static Map<String, Object> snapshot(PortalConfiguration c) {
        return new java.util.LinkedHashMap<>(Map.of("slug", c.getSlug(), "enabled", c.isEnabled(), "displayName",
                c.getDisplayName(), "primaryColor", c.getPrimaryColor(), "accentColor", c.getAccentColor(), "portfolios",
                c.getPortfolioIds(), "otpChannels", c.getOtpChannels(), "knowledgeChallenge", c.getKnowledgeChallenge(),
                "linkValidityHours", c.getLinkValidityHours(), "privacyNoticeVersion", c.getPrivacyNoticeVersion()));
    }

    /** Remove o arquivo antigo após o commit, ou o novo se a transação for desfeita. */
    private void cleanupAfterCompletion(String newKey, String previousKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String obsolete = status == STATUS_COMMITTED ? previousKey : newKey;
                if (obsolete != null) {
                    fileStorage.delete(obsolete);
                }
            }
        });
    }

    private static byte[] readLimited(InputStream in, int max) {
        try (in) {
            byte[] bytes = in.readNBytes(max + 1);
            Guard.require(bytes.length > 0, "portal.logo_required", "Arquivo vazio");
            Guard.require(bytes.length <= max, "portal.logo_too_large", "Logotipo até 256 KB");
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ConfigurationView view(PortalConfiguration c) {
        return new ConfigurationView(c.getId(), c.getCreditorId(), c.getSlug(), c.isEnabled(), c.getDisplayName(),
                c.getPrimaryColor(), c.getAccentColor(), c.getLogoFileKey() != null, c.getLogoSha256(),
                c.getPortfolioIds(), c.getOtpChannels().stream().map(Enum::name).toList(), c.getKnowledgeChallenge().name(),
                c.getLinkValidityHours(), c.getPrivacyNoticeVersion(), c.getPrivacyNoticeText(), c.getSupportText(),
                properties.publicBaseUrl() + "/portal/" + c.getSlug(), c.getUpdatedAt(),
                c.getVersion() == null ? 0 : c.getVersion());
    }

    private static SessionSummary session(PortalSession s) {
        return new SessionSummary(s.getId(), s.getDebtorId(), s.getMethod().name(), s.getStatus().name(), s.getCreatedAt(),
                s.getLastSeenAt(), s.getExpiresAt(), s.getEndedAt(), s.getEndReason());
    }
}
