package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.SessionGrant;
import br.com.gestorcobranca.portal.application.port.in.PortalSessionUseCases;
import br.com.gestorcobranca.portal.application.port.out.PortalTokenIssuer;
import br.com.gestorcobranca.portal.application.port.out.PortalTokenIssuer.IssuedToken;
import br.com.gestorcobranca.portal.application.service.PortalContext.Current;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.model.SessionStatus;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.portal.PortalPrivacyAcknowledged;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AuthenticationFailedException;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional
class PortalSessionService implements PortalSessionUseCases {

    private final PortalContext context;
    private final PortalSessionRepository sessions;
    private final PortalTokenIssuer tokens;
    private final PortalDebts debts;
    private final PortalProperties properties;
    private final AccessLogger accessLogger;
    private final CurrentActor currentActor;
    private final ApplicationEventPublisher events;
    private final PortalMetrics metrics;
    private final Clock clock;

    PortalSessionService(PortalContext context, PortalSessionRepository sessions, PortalTokenIssuer tokens, PortalDebts debts,
                   PortalProperties properties, AccessLogger accessLogger, CurrentActor currentActor,
                   ApplicationEventPublisher events, PortalMetrics metrics, Clock clock) {
        this.context = context;
        this.sessions = sessions;
        this.tokens = tokens;
        this.debts = debts;
        this.properties = properties;
        this.accessLogger = accessLogger;
        this.currentActor = currentActor;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SessionView current() {
        return view(context.requireSession());
    }

    @Override
    public SessionGrant refresh() {
        Current c = context.requireSession();
        IssuedToken token = tokens.issue(c.config().getTenantId(), c.session().getId(), c.session().getDebtorId(),
                c.session().getCreditorId());
        metrics.session("REFRESHED");
        return new SessionGrant(token.value(), token.expiresAt(), c.session().getId());
    }

    @Override
    public void logout() {
        Current c = context.requireSession();
        PortalSession session = c.session();
        if (session.end(SessionStatus.ENDED, "logout", Instant.now(clock))) {
            sessions.save(session);
            accessLogger.log(AccessEvent.of(AccessEventType.PORTAL_LOGOUT, currentActor.get(), Map.of()));
            metrics.session("LOGOUT");
        }
    }

    @Override
    public SessionView acknowledgePrivacy(int noticeVersion) {
        Current c = context.requireSession();
        if (noticeVersion != c.config().getPrivacyNoticeVersion()) {
            throw new ConflictException("portal.privacy_notice_outdated",
                    "O aviso de privacidade foi atualizado. Leia a versão atual para continuar.");
        }
        Instant now = Instant.now(clock);
        PortalSession session = c.session();
        if (!session.privacyAcknowledged(noticeVersion)) {
            session.acknowledgePrivacy(noticeVersion, now);
            sessions.save(session);
            accessLogger.log(AccessEvent.of(AccessEventType.PORTAL_PRIVACY_ACKNOWLEDGED, currentActor.get(),
                    Map.of("noticeVersion", String.valueOf(noticeVersion))));
            // registro no compliance (o grafo não permite portal → compliance): reconhecimento + base legal vigente
            events.publishEvent(new PortalPrivacyAcknowledged(Ids.newId(), c.config().getTenantId(), now,
                    session.getDebtorId(), session.getCreditorId(), session.getId(), noticeVersion,
                    c.config().getPrivacyNoticeSha256()));
        }
        return view(new Current(c.actor(), session, c.config()));
    }

    private SessionView view(Current c) {
        PortalSession s = c.session();
        String firstName;
        try {
            firstName = firstName(debts.debtor(s.getDebtorId()).name());
        } catch (RuntimeException e) {
            throw AuthenticationFailedException.invalidToken();
        }
        return new SessionView(s.getId(), c.config().getSlug(), c.config().getDisplayName(), firstName, s.getMethod().name(),
                s.getExpiresAt(), properties.session().idleTimeout().toSeconds(),
                s.privacyAcknowledged(c.config().getPrivacyNoticeVersion()), c.config().getPrivacyNoticeVersion());
    }

    /** Primeiro nome com inicial maiúscula (minimização: a tela não precisa do nome completo). */
    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String first = name.strip().split("\\s+")[0].toLowerCase(Locale.of("pt", "BR"));
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }
}
