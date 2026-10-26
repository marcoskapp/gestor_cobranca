package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases;
import br.com.gestorcobranca.portal.domain.model.AccessLink;
import br.com.gestorcobranca.portal.domain.model.PortalChannel;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.repository.AccessLinkRepository;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Link de acesso para envio automático (régua): mesmas regras do link do operador
 * ({@link AccessLinkIssuer}), com a carteira do caso habilitada no portal como condição extra — a régua
 * trabalha um caso (devedor × carteira). A indisponibilidade é resultado, não erro: quem envia pula a
 * etapa com o motivo.
 */
@Service
@Transactional
class PortalLinkService implements PortalLinkUseCases {

    private static final Pattern ORIGIN = Pattern.compile("[A-Z][A-Z0-9_]{1,29}");

    private final PortalConfigurationRepository configurations;
    private final AccessLinkRepository links;
    private final PortalDebts debts;
    private final AccessScope accessScope;
    private final AccessLinkIssuer issuer;

    PortalLinkService(PortalConfigurationRepository configurations, AccessLinkRepository links, PortalDebts debts,
                      AccessScope accessScope, AccessLinkIssuer issuer) {
        this.configurations = configurations;
        this.links = links;
        this.debts = debts;
        this.accessScope = accessScope;
        this.issuer = issuer;
    }

    @Override
    public LinkIssue issue(IssueLinkCommand command) {
        accessScope.requirePermission(Permission.MESSAGE_SEND);
        Guard.require(command != null && command.debtorId() != null && command.creditorId() != null
                && command.portfolioId() != null, "portal.link_target_required", "Devedor, credor e carteira obrigatórios");
        requireOrigin(command.origin(), command.reference());
        accessScope.requireCreditor(command.creditorId());
        PortalChannel channel = PortalAccessService.channel(command.channel());
        Optional<PortalConfiguration> enabled = configurations.findByCreditorId(command.creditorId())
                .filter(PortalConfiguration::isEnabled);
        if (enabled.isEmpty()) {
            return LinkIssue.unavailable(LinkIssue.PORTAL_NOT_ENABLED);
        }
        PortalConfiguration config = enabled.get();
        if (!config.coversPortfolio(command.portfolioId())) {
            return LinkIssue.unavailable(LinkIssue.PORTFOLIO_NOT_IN_PORTAL);
        }
        if (!debts.hasDebtsInPortal(config, command.debtorId())) {
            return LinkIssue.unavailable(LinkIssue.DEBTOR_NOT_IN_PORTAL);
        }
        AccessLinkIssuer.Issued issued = issuer.issue(config, command.debtorId(), channel);
        return new LinkIssue(null, issued.link().getId(), issued.url(), config.getLinkValidityHours(),
                issued.link().getExpiresAt());
    }

    @Override
    public void recordDispatch(DispatchCommand command) {
        accessScope.requirePermission(Permission.MESSAGE_SEND);
        Guard.require(command != null && command.linkId() != null && command.messageId() != null,
                "portal.link_dispatch_invalid", "Link e mensagem obrigatórios");
        requireOrigin(command.origin(), command.reference());
        AccessLink link = links.findById(command.linkId())
                .orElseThrow(() -> ResourceNotFoundException.of("portal_access_link", command.linkId()));
        accessScope.requireCreditor(link.getCreditorId());
        Guard.requireState(link.getMessageId() == null, "portal.link_already_dispatched", "Link já vinculado a uma mensagem");
        issuer.recordDispatch(link, command.messageId(), command.messageStatus(), command.origin(), command.reference());
    }

    private static void requireOrigin(String origin, String reference) {
        Guard.require(origin != null && ORIGIN.matcher(origin).matches(), "portal.link_origin_invalid",
                "Origem do pedido de link inválida");
        Guard.require(reference == null || reference.length() <= 120, "portal.link_reference_invalid",
                "Referência com até 120 caracteres");
    }
}
