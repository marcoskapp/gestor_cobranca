package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.out.AccessThrottleStore;
import br.com.gestorcobranca.portal.application.port.out.PortalAgreementLedger;
import br.com.gestorcobranca.portal.domain.model.AccessChallenge;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.model.ServiceRequest;
import br.com.gestorcobranca.portal.domain.model.SessionStatus;
import br.com.gestorcobranca.portal.domain.repository.AccessChallengeRepository;
import br.com.gestorcobranca.portal.domain.repository.AccessLinkRepository;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reações e manutenção do portal (executadas no tenant, como sistema, pelos consumidores e jobs):
 * pagamento confirmado de acordo fechado no portal (etapa final do funil), anonimização do devedor
 * (revoga sessões e links e apaga IP/agente, índice do documento e mensagens livres), expiração das
 * sessões vencidas e retenção dos dados de acesso ({@link #purgeAccessData}).
 */
@Component
class PortalMaintenance {

    static final int BATCH = 500;

    private final PortalAgreementLedger ledger;
    private final PortalSessionRepository sessions;
    private final AccessLinkRepository links;
    private final AccessChallengeRepository challenges;
    private final ServiceRequestRepository serviceRequests;
    private final PortalConfigurationRepository configurations;
    private final AccessThrottleStore throttle;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final PortalProperties properties;
    private final PortalMetrics metrics;
    private final Clock clock;

    PortalMaintenance(PortalAgreementLedger ledger, PortalSessionRepository sessions, AccessLinkRepository links,
                      AccessChallengeRepository challenges, ServiceRequestRepository serviceRequests,
                      PortalConfigurationRepository configurations, AccessThrottleStore throttle,
                      AccessLogger accessLogger, BusinessEventLogger businessEvents, PortalProperties properties,
                      PortalMetrics metrics, Clock clock) {
        this.ledger = ledger;
        this.sessions = sessions;
        this.links = links;
        this.challenges = challenges;
        this.serviceRequests = serviceRequests;
        this.configurations = configurations;
        this.throttle = throttle;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    void paymentConfirmed(PaymentConfirmed e) {
        if (e.agreementId() == null) {
            return;
        }
        ledger.find(e.agreementId()).ifPresent(entry -> {
            if (ledger.markFirstPaid(e.agreementId(), e.paidAt() == null ? e.occurredAt() : e.paidAt())) {
                metrics.funnel(PortalMetrics.Step.PAYMENT_CONFIRMED);
                businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_PAYMENT_CONFIRMED).creditor(entry.creditorId())
                        .portfolio(entry.portfolioId()).debtor(entry.debtorId())
                        .reference("negotiation_agreement", entry.agreementId()).channel("PORTAL").amount(e.amount())
                        .attr("installments", entry.installments()).attr("method", e.method())
                        .durationMs(java.time.Duration.between(entry.formalizedAt(),
                                e.paidAt() == null ? e.occurredAt() : e.paidAt()).toMillis()));
            }
        });
    }

    void debtorAnonymized(DebtorAnonymized e) {
        Instant now = Instant.now(clock);
        for (PortalSession s : sessions.findByDebtorId(e.debtorId())) {
            if (s.end(SessionStatus.REVOKED, "debtor_anonymized", now)) {
                accessLogger.log(AccessEvent.forDebtor(AccessEventType.PORTAL_SESSION_REVOKED, e.tenantId(), e.debtorId(),
                        s.getId(), Map.of("reason", "debtor_anonymized")));
                metrics.session("REVOKED");
            }
            s.anonymize();
            sessions.save(s);
        }
        configurations.findAllByOrderByDisplayNameAsc().forEach(c -> links.findOpen(c.getId(), e.debtorId()).forEach(l -> {
            l.revoke(now);
            links.save(l);
        }));
        for (AccessChallenge c : challenges.findByDebtorId(e.debtorId())) {
            c.anonymize();
            challenges.save(c);
        }
        for (ServiceRequest r : serviceRequests.findByDebtorId(e.debtorId())) {
            r.anonymize();
            serviceRequests.save(r);
        }
    }

    /** Resultado de uma rodada da retenção (cada tipo limitado a {@link #BATCH}). */
    record Purged(int challenges, int throttles, int sessions) {

        boolean full() {
            return challenges == BATCH || throttles == BATCH || sessions == BATCH;
        }

        int total() {
            return challenges + throttles + sessions;
        }
    }

    /**
     * Retenção dos dados de acesso (LGPD, minimização), uma rodada de até {@link #BATCH} por tipo: desafios vencidos
     * (inclusive os fictícios, com o HMAC do documento de quem não é devedor), contadores do bloqueio progressivo
     * sem uso e sem bloqueio vigente, e IP/agente das sessões encerradas. Prazos em {@code app.portal.retention}.
     */
    Purged purgeAccessData() {
        Instant now = Instant.now(clock);
        PortalProperties.Retention r = properties.retention();
        Purged purged = new Purged(
                challenges.deleteExpiredBefore(now.minus(r.challenges()), BATCH),
                throttle.deleteIdleBefore(now.minus(r.throttles()), now, BATCH),
                sessions.clearClientDataEndedBefore(now.minus(r.sessionClientData()), BATCH));
        metrics.retention("CHALLENGE", purged.challenges());
        metrics.retention("THROTTLE", purged.throttles());
        metrics.retention("SESSION_CLIENT_DATA", purged.sessions());
        return purged;
    }

    /** @return sessões expiradas neste lote */
    int expireSessions() {
        Instant now = Instant.now(clock);
        List<PortalSession> stale = sessions.findActiveStale(now, now.minus(properties.session().idleTimeout()), BATCH);
        for (PortalSession s : stale) {
            if (s.end(SessionStatus.EXPIRED, s.getExpiresAt().isAfter(now) ? "idle" : "absolute", now)) {
                sessions.save(s);
                metrics.session("EXPIRED");
            }
        }
        return stale.size();
    }
}
