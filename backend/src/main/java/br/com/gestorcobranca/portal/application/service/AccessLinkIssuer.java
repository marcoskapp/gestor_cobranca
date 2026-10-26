package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.domain.model.AccessLink;
import br.com.gestorcobranca.portal.domain.model.PortalChannel;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.repository.AccessLinkRepository;
import br.com.gestorcobranca.portal.domain.service.AccessSecrets;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Geração e registro do envio do link de acesso de uso único — regra única para o link enviado
 * pelo operador e pela régua: os links abertos do devedor no portal são revogados, o token
 * ({@link AccessSecrets#newLinkToken()}) só existe na URL devolvida (o banco guarda o SHA-256) e vai
 * no fragmento ({@code #token}, fora de logs de proxy e do {@code Referer}); o envio negado ou
 * recusado revoga o link; o log de acesso ({@code PORTAL_LINK_ISSUED}), a auditoria e a métrica
 * nunca levam a URL.
 */
@Component
class AccessLinkIssuer {

    private final AccessLinkRepository links;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final PortalProperties properties;
    private final PortalMetrics metrics;
    private final Clock clock;

    AccessLinkIssuer(AccessLinkRepository links, CurrentActor currentActor, AccessLogger accessLogger,
                     AuditTrail auditTrail, PortalProperties properties, PortalMetrics metrics, Clock clock) {
        this.links = links;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Link novo e sua URL (com o token). @param url só para a mensagem — nunca registrar */
    record Issued(AccessLink link, String url) {

        @Override
        public String toString() {
            return "Issued[linkId=" + link.getId() + ", url=***]";
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Issued issue(PortalConfiguration config, UUID debtorId, PortalChannel channel) {
        Instant now = Instant.now(clock);
        links.findOpen(config.getId(), debtorId).forEach(l -> {
            l.revoke(now);
            links.save(l);
        });
        Actor actor = currentActor.get();
        String token = AccessSecrets.newLinkToken();
        Duration validity = Duration.ofHours(config.getLinkValidityHours());
        AccessLink link = links.save(new AccessLink(config.getId(), config.getCreditorId(), debtorId,
                AccessSecrets.sha256(token), channel, actor.type().name(), actor.id(), now.plus(validity)));
        String url = properties.publicBaseUrl() + "/portal/" + config.getSlug() + "/entrar#" + token;
        return new Issued(link, url);
    }

    /**
     * Vincula a mensagem ao link; {@code FAILED} (porteiro negou, envio recusado) revoga o link — ninguém
     * o recebeu. Registra {@code PORTAL_LINK_ISSUED}, a auditoria e a métrica.
     *
     * @param origin    {@code OPERATOR} ou quem pediu pelo caso de uso (ex.: {@code CAMPAIGN})
     * @param reference referência de quem pediu (nula para o operador)
     * @return {@code true} se a mensagem não falhou
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean recordDispatch(AccessLink link, UUID messageId, String messageStatus, String origin, String reference) {
        link.sentAs(messageId);
        boolean failed = "FAILED".equals(messageStatus);
        if (failed) {
            link.revoke(Instant.now(clock));
        }
        links.save(link);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("debtorId", link.getDebtorId().toString());
        details.put("linkId", link.getId().toString());
        details.put("channel", link.getChannel().name());
        details.put("delivered", String.valueOf(!failed));
        details.put("origin", origin);
        if (reference != null) {
            details.put("reference", reference);
        }
        accessLogger.log(AccessEvent.of(AccessEventType.PORTAL_LINK_ISSUED, currentActor.get(), details));
        auditTrail.record(AuditEntry.of("portal.access_link_issued", "portal_access_link", link.getId())
                .creditor(link.getCreditorId()).created("channel", link.getChannel())
                .created("expiresAt", link.getExpiresAt()).meta("debtorId", link.getDebtorId())
                .meta("messageId", messageId).meta("messageStatus", messageStatus).meta("origin", origin)
                .meta("reference", reference));
        metrics.linkIssued(link.getChannel().name(), failed ? "NOT_DELIVERED" : "SENT");
        return !failed;
    }
}
