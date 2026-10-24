package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases;
import br.com.gestorcobranca.portal.domain.model.AccessLink;
import br.com.gestorcobranca.portal.domain.model.PortalChannel;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.model.ServiceRequest;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestStatus;
import br.com.gestorcobranca.portal.domain.repository.AccessLinkRepository;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import br.com.gestorcobranca.portal.domain.service.AccessSecrets;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class PortalOperationsService implements PortalOperationsUseCases {

    private final PortalConfigurationRepository configurations;
    private final AccessLinkRepository links;
    private final ServiceRequestRepository serviceRequests;
    private final PortalDebts debts;
    private final MessageUseCases messages;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final PortalProperties properties;
    private final PortalMetrics metrics;
    private final Clock clock;

    PortalOperationsService(PortalConfigurationRepository configurations, AccessLinkRepository links,
                      ServiceRequestRepository serviceRequests, PortalDebts debts, MessageUseCases messages,
                      AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger, AuditTrail auditTrail,
                      PortalProperties properties, PortalMetrics metrics, Clock clock) {
        this.configurations = configurations;
        this.links = links;
        this.serviceRequests = serviceRequests;
        this.debts = debts;
        this.messages = messages;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Novo link de uso único (os anteriores abertos do devedor no portal são revogados), enviado pelo
     * communication com o operador como autor; o token só existe na mensagem (o banco guarda o SHA-256).
     * Mensagem negada pelo porteiro → o link é revogado na hora (ninguém o recebeu).
     */
    @Override
    public AccessLinkView sendAccessLink(SendLinkCommand command) {
        accessScope.requirePermission(Permission.MESSAGE_SEND);
        Guard.require(command != null && command.debtorId() != null && command.creditorId() != null,
                "portal.link_target_required", "Devedor e credor obrigatórios");
        accessScope.requireCreditor(command.creditorId());
        PortalChannel channel = PortalAccessService.channel(command.channel());
        PortalConfiguration config = configurations.findByCreditorId(command.creditorId())
                .filter(PortalConfiguration::isEnabled)
                .orElseThrow(() -> new ConflictException("portal.not_enabled", "O credor não tem portal habilitado"));
        Guard.require(debts.hasDebtsInPortal(config, command.debtorId()), "portal.debtor_not_in_portal",
                "O devedor não tem dívida nas carteiras habilitadas no portal deste credor");
        Instant now = Instant.now(clock);
        links.findOpen(config.getId(), command.debtorId()).forEach(l -> {
            l.revoke(now);
            links.save(l);
        });
        Actor actor = currentActor.get();
        String token = AccessSecrets.newLinkToken();
        Duration validity = Duration.ofHours(config.getLinkValidityHours());
        AccessLink link = links.save(new AccessLink(config.getId(), config.getCreditorId(), command.debtorId(),
                AccessSecrets.sha256(token), channel, actor.type().name(), actor.id(), now.plus(validity)));
        String url = properties.publicBaseUrl() + "/portal/" + config.getSlug() + "/entrar#" + token;
        MessageView message = messages.send(new SendMessageCommand(null, command.debtorId(), command.creditorId(), null,
                channel.name(), null, PortalAccessService.template(properties.templates().accessLink(), channel),
                Map.of("link", url, "horas", String.valueOf(config.getLinkValidityHours())), null, null,
                "portal-link:" + link.getId()));
        link.sentAs(message.id());
        boolean failed = "FAILED".equals(message.status());
        if (failed) {
            link.revoke(now);
        }
        links.save(link);
        accessLogger.log(AccessEvent.of(AccessEventType.PORTAL_LINK_ISSUED, actor, Map.of("debtorId",
                command.debtorId().toString(), "linkId", link.getId().toString(), "channel", channel.name(),
                "delivered", String.valueOf(!failed))));
        auditTrail.record(AuditEntry.of("portal.access_link_issued", "portal_access_link", link.getId())
                .creditor(config.getCreditorId()).created("channel", channel).created("expiresAt", link.getExpiresAt())
                .meta("debtorId", command.debtorId()).meta("messageId", message.id()).meta("messageStatus", message.status()));
        metrics.linkIssued(channel.name(), failed ? "NOT_DELIVERED" : "SENT");
        return new AccessLinkView(link.getId(), command.debtorId(), command.creditorId(), channel.name(),
                link.getExpiresAt(), message.id(), message.status(), message.failureCode());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ServiceRequestSummary> serviceRequests(UUID creditorId, String status, PageQuery page) {
        accessScope.requirePermission(Permission.INBOX_VIEW);
        if (creditorId != null) {
            accessScope.requireCreditor(creditorId);
        }
        ServiceRequestStatus st = status == null || status.isBlank() ? null
                : PortalSelfServiceService.parse(ServiceRequestStatus.class, status, "portal.request_status_invalid");
        List<UUID> allowed = accessScope.hasAllCreditors() ? null : List.copyOf(accessScope.restrictCreditors(List.of()));
        return PageResponse.of(serviceRequests.search(allowed, creditorId, st,
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))), PortalOperationsService::summary);
    }

    private static ServiceRequestSummary summary(ServiceRequest r) {
        return new ServiceRequestSummary(r.getId(), r.getProtocol(), r.getCreditorId(), r.getPortfolioId(), r.getDebtorId(),
                r.getReason().name(), r.getPreferredChannel().name(), r.getStatus().name(), r.getNote(),
                r.getConversationId(), r.getFailureCode(), r.getCreatedAt(), r.getForwardedAt());
    }
}
