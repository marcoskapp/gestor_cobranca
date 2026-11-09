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
import br.com.gestorcobranca.portal.domain.model.ServiceRequestDispute;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestStatus;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestDisputeRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
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

    static final String RESOURCE = "portal_service_request";

    private final PortalConfigurationRepository configurations;
    private final ServiceRequestRepository serviceRequests;
    private final ServiceRequestDisputeRepository requestDisputes;
    private final PortalDebts debts;
    private final MessageUseCases messages;
    private final AccessScope accessScope;
    private final AccessLinkIssuer issuer;
    private final PortalProperties properties;
    private final AccessLogger accessLogger;
    private final CurrentActor currentActor;

    PortalOperationsService(PortalConfigurationRepository configurations, ServiceRequestRepository serviceRequests,
                            ServiceRequestDisputeRepository requestDisputes, PortalDebts debts, MessageUseCases messages, AccessScope accessScope,
                            AccessLinkIssuer issuer, PortalProperties properties, AccessLogger accessLogger,
                            CurrentActor currentActor) {
        this.configurations = configurations;
        this.serviceRequests = serviceRequests;
        this.requestDisputes = requestDisputes;
        this.debts = debts;
        this.messages = messages;
        this.accessScope = accessScope;
        this.issuer = issuer;
        this.properties = properties;
        this.accessLogger = accessLogger;
        this.currentActor = currentActor;
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
        var found = serviceRequests.search(allowed, creditorId, st,
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        Map<UUID, ServiceRequestDispute> intake = new java.util.HashMap<>();
        requestDisputes.findByRequestIdIn(found.stream().map(ServiceRequest::getId).toList())
                .forEach(d -> intake.put(d.getRequestId(), d));
        return PageResponse.of(found, r -> summary(r, intake.get(r.getId())));
    }

    /**
     * Detalhe com a mensagem livre do devedor (pode ter dado de saúde ou de terceiros): decifrada só aqui, com
     * {@code SENSITIVE_DATA_VIEWED} no log de acesso (ids, sem conteúdo).
     */
    @Override
    @Transactional(readOnly = true)
    public ServiceRequestDetail serviceRequest(UUID requestId) {
        accessScope.requirePermission(Permission.INBOX_VIEW);
        ServiceRequest r = serviceRequests.findById(requestId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, requestId));
        accessScope.requireVisible(RESOURCE, requestId, r.getCreditorId());
        String note = r.getNote();
        if (note != null) {
            accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(), Map.of(
                    "resource", RESOURCE, "resourceId", requestId.toString(), "field", "note")));
        }
        return new ServiceRequestDetail(summary(r, requestDisputes.findByRequestId(r.getId()).orElse(null)), note);
    }

    /** Listagem sem a mensagem do devedor (minimização): só se ela existe. */
    private static ServiceRequestSummary summary(ServiceRequest r, ServiceRequestDispute intake) {
        return new ServiceRequestSummary(r.getId(), r.getProtocol(), r.getCreditorId(), r.getPortfolioId(), r.getDebtorId(),
                r.getReason().name(), r.getPreferredChannel().name(), r.getStatus().name(), r.hasNote(),
                r.getConversationId(), r.getFailureCode(), r.getCreatedAt(), r.getForwardedAt(), r.getDisputeType(),
                intake == null ? null : intake.getStatus(), intake == null ? null : intake.getDisputesOpened());
    }
}
