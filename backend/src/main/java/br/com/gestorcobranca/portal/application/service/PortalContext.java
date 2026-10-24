package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.AuthenticationFailedException;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Contexto do devedor autenticado no portal: o ator {@code DEBTOR} (id = devedor), a sessão (validada
 * de novo aqui, além do resolvedor do token) e a configuração do portal da sessão. Tudo o que o portal
 * consulta ou executa usa <strong>somente</strong> o devedor e o credor daqui — nunca ids informados
 * pelo cliente para o devedor.
 */
@Component
class PortalContext {

    record Current(Actor actor, PortalSession session, PortalConfiguration config) {
    }

    private final CurrentActor currentActor;
    private final PortalSessionRepository sessions;
    private final PortalConfigurationRepository configurations;
    private final PortalProperties properties;
    private final AccessLogger accessLogger;
    private final Clock clock;

    PortalContext(CurrentActor currentActor, PortalSessionRepository sessions,
                  PortalConfigurationRepository configurations, PortalProperties properties, AccessLogger accessLogger,
                  Clock clock) {
        this.currentActor = currentActor;
        this.sessions = sessions;
        this.configurations = configurations;
        this.properties = properties;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    /** Sessão válida e aviso de privacidade vigente reconhecido (exigido para consultar e negociar). */
    Current require() {
        Current c = requireSession();
        if (!c.session().privacyAcknowledged(c.config().getPrivacyNoticeVersion())) {
            throw new ConflictException("portal.privacy_ack_required",
                    "Leia e confirme o aviso de privacidade para continuar");
        }
        return c;
    }

    /** Sessão válida (sem exigir o aviso — usado pelas operações da própria sessão). */
    Current requireSession() {
        Actor actor = currentActor.get();
        if (actor.type() != Actor.ActorType.DEBTOR || actor.sessionId() == null) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("resource", "portal")));
            throw new AuthenticationFailedException(PortalAccessService.INVALID_CODE, "Sessão do portal obrigatória");
        }
        Instant now = Instant.now(clock);
        PortalSession session = sessions.findById(actor.sessionId())
                .filter(s -> s.getDebtorId().equals(actor.id()) && s.usableAt(now, properties.session().idleTimeout()))
                .orElseThrow(AuthenticationFailedException::invalidToken);
        PortalConfiguration config = configurations.findById(session.getConfigurationId())
                .filter(PortalConfiguration::isEnabled)
                .orElseThrow(AuthenticationFailedException::invalidToken);
        return new Current(actor, session, config);
    }
}
