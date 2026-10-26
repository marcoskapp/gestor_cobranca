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
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class PortalOperationsService implements PortalOperationsUseCases {

    private final PortalConfigurationRepository configurations;
    private final ServiceRequestRepository serviceRequests;
    private final PortalDebts debts;
    private final MessageUseCases messages;
    private final AccessScope accessScope;
    private final AccessLinkIssuer issuer;
    private final PortalProperties properties;

    PortalOperationsService(PortalConfigurationRepository configurations, ServiceRequestRepository serviceRequests,
                            PortalDebts debts, MessageUseCases messages, AccessScope accessScope,
                            AccessLinkIssuer issuer, PortalProperties properties) {
        this.configurations = configurations;
        this.serviceRequests = serviceRequests;
        this.debts = debts;
        this.messages = messages;
        this.accessScope = accessScope;
        this.issuer = issuer;
        this.properties = properties;
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
        AccessLinkIssuer.Issued issued = issuer.issue(config, command.debtorId(), channel);
        AccessLink link = issued.link();
        MessageView message = messages.send(new SendMessageCommand(null, command.debtorId(), command.creditorId(), null,
                channel.name(), null, PortalAccessService.template(properties.templates().accessLink(), channel),
                Map.of("link", issued.url(), "horas", String.valueOf(config.getLinkValidityHours())), null, null,
                "portal-link:" + link.getId()));
        issuer.recordDispatch(link, message.id(), message.status(), "OPERATOR", null);
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
