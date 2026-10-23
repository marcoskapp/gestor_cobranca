package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.ClientInfo;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.SessionGrant;
import br.com.gestorcobranca.portal.application.port.out.PortalTokenIssuer;
import br.com.gestorcobranca.portal.application.port.out.PortalTokenIssuer.IssuedToken;
import br.com.gestorcobranca.portal.domain.model.AccessMethod;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.model.SessionStatus;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Abertura da sessão do portal após a verificação (código, link e, se exigido, conhecimento):
 * respeita o limite de sessões simultâneas por devedor (revoga as mais antigas), emite o token do
 * portal e registra {@code PORTAL_LOGIN_SUCCESS}, {@code portal.accessed} e a etapa "acesso" do funil.
 */
@Component
class SessionOpener {

    private final PortalSessionRepository sessions;
    private final PortalTokenIssuer tokens;
    private final PortalProperties properties;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final PortalMetrics metrics;

    SessionOpener(PortalSessionRepository sessions, PortalTokenIssuer tokens, PortalProperties properties,
                  AccessLogger accessLogger, BusinessEventLogger businessEvents, PortalMetrics metrics) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.properties = properties;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
    }

    SessionGrant open(PortalConfiguration config, UUID debtorId, AccessMethod method, ClientInfo client, Instant now) {
        UUID tenantId = config.getTenantId();
        List<PortalSession> active = sessions.findActiveByDebtorId(debtorId);
        int excess = active.size() - (properties.session().maxActivePerDebtor() - 1);
        for (int i = 0; i < excess; i++) {
            PortalSession old = active.get(i);
            if (old.end(SessionStatus.REVOKED, "session_limit", now)) {
                sessions.save(old);
                accessLogger.log(AccessEvent.forDebtor(AccessEventType.PORTAL_SESSION_REVOKED, tenantId, debtorId,
                        old.getId(), Map.of("reason", "session_limit")));
                metrics.session("REVOKED");
            }
        }
        PortalSession session = sessions.save(new PortalSession(config.getId(), config.getCreditorId(), debtorId, method,
                now, properties.session().absoluteTimeout(), client == null ? null : client.ip(),
                client == null ? null : client.userAgent()));
        IssuedToken token = tokens.issue(tenantId, session.getId(), debtorId, config.getCreditorId());
        accessLogger.log(AccessEvent.forDebtor(AccessEventType.PORTAL_LOGIN_SUCCESS, tenantId, debtorId, session.getId(),
                Map.of("method", method.name(), "portal", config.getSlug())));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_ACCESSED).creditor(config.getCreditorId())
                .debtor(debtorId).channel("PORTAL").reference("portal_session", session.getId())
                .attr("method", method.name()).attr("knowledgeChallenge", config.getKnowledgeChallenge().name()));
        metrics.session("OPENED");
        metrics.login(method.name(), "SUCCESS");
        metrics.funnel(PortalMetrics.Step.ACCESS);
        return new SessionGrant(token.value(), token.expiresAt(), session.getId());
    }
}
