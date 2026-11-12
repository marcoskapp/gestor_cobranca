package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.RepresentativeView;
import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.out.AccessThrottleStore;
import br.com.gestorcobranca.portal.application.service.PortalContext.Current;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.portal.domain.service.DeclaredName;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.KeyType;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.State;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Confirmação de quem negocia pela empresa no portal (cobrança B2B, B4). Regras:
 * <ul>
 *   <li>o CPF digitado é conferido com os representantes com poder para negociar e vigentes pelo blind index do debt
 *       (finalidade própria) e o primeiro e o último nome declarados precisam bater com o cadastro
 *       ({@link DeclaredName}, sem acento e sem diferenciar maiúsculas); CPF e nome nunca vão a log técnico, evento,
 *       métrica ou auditoria — a sessão guarda só a forma mascarada do CPF e o nome declarado (criptografado), para o
 *       atendente;</li>
 *   <li>a resposta não muda entre "confere" e "não confere" (anti-enumeração); quem não confere segue consultando e o
 *       aceite vira atendimento;</li>
 *   <li>falha conta por devedor PJ ({@code REP_DEBTOR}) e por IP ({@code REP_IP}) no bloqueio progressivo persistido
 *       (renovar a sessão não zera); bloqueado, a declaração é registrada sem conferir. As duas linhas do contador
 *       são travadas ({@code for update}, empresa e depois IP) antes de decidir e a trava vale até o commit da
 *       transação do chamador ({@code declareRepresentative}, {@code @Transactional}).</li>
 * </ul>
 */
@Component
class RepresentativeConfirmation {

    static final String DEBTOR_PURPOSE = "portal.representative.debtor";
    static final String IP_PURPOSE = "portal.representative.ip";

    private final CompanyRepresentativeUseCases representatives;
    private final PortalSessionRepository sessions;
    private final AccessThrottleStore throttle;
    private final BlindIndex blindIndex;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final PortalProperties properties;
    private final PortalMetrics metrics;
    private final Clock clock;

    RepresentativeConfirmation(CompanyRepresentativeUseCases representatives, PortalSessionRepository sessions,
                               AccessThrottleStore throttle, BlindIndex blindIndex, AccessLogger accessLogger,
                               BusinessEventLogger businessEvents, PortalProperties properties, PortalMetrics metrics,
                               Clock clock) {
        this.representatives = representatives;
        this.sessions = sessions;
        this.throttle = throttle;
        this.blindIndex = blindIndex;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Registra a declaração na sessão (transação do chamador). @return verdadeiro se confirmou */
    boolean declare(Current c, PortalSession session, String rawCpf, String name, String ip) {
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 200, "portal.representative_name_required",
                "Informe o nome de quem está negociando");
        Document cpf = cpf(rawCpf);
        UUID companyId = session.getDebtorId();
        Instant now = Instant.now(clock);
        String debtorKey = blindIndex.of(c.config().getTenantId(), DEBTOR_PURPOSE, companyId.toString());
        String ipKey = blindIndex.of(c.config().getTenantId(), IP_PURPOSE, ip == null || ip.isBlank() ? "unknown" : ip);
        // trava as duas linhas (empresa e IP, sempre nessa ordem) antes de decidir: declarações simultâneas esperam o
        // commit da anterior e não furam o limite; "|" (não "||") para travar sempre as duas
        boolean locked = throttle.update(KeyType.REP_DEBTOR, debtorKey, now, s -> s).lockedAt(now)
                | throttle.update(KeyType.REP_IP, ipKey, now, s -> s).lockedAt(now);
        // confere o CPF e o primeiro e último nome declarados; nome que não confere conta como falha, mesma resposta
        Optional<RepresentativeView> match = locked ? Optional.empty()
                : representatives.negotiator(companyId, cpf.number())
                        .filter(r -> DeclaredName.matches(r.name(), name));
        String outcome;
        if (locked) {
            outcome = "LOCKED";
            metrics.locked("REPRESENTATIVE", "REFUSED");
        } else if (match.isPresent()) {
            outcome = "CONFIRMED";
            throttle.update(KeyType.REP_DEBTOR, debtorKey, now, s -> ThrottlePolicy.onSuccess(s, now, true));
            throttle.update(KeyType.REP_IP, ipKey, now, s -> ThrottlePolicy.onSuccess(s, now, false));
        } else {
            outcome = "UNCONFIRMED";
            State debtorState = throttle.update(KeyType.REP_DEBTOR, debtorKey, now,
                    s -> ThrottlePolicy.onFailure(s, limits(properties.representative().debtorMaxFailures()), now));
            State ipState = throttle.update(KeyType.REP_IP, ipKey, now,
                    s -> ThrottlePolicy.onFailure(s, limits(properties.representative().ipMaxFailures()), now));
            if (debtorState.lockedAt(now)) {
                lockApplied(c, KeyType.REP_DEBTOR, debtorState.lockedUntil());
            }
            if (ipState.lockedAt(now)) {
                lockApplied(c, KeyType.REP_IP, ipState.lockedUntil());
            }
        }
        session.declareRepresentative(match.map(RepresentativeView::id).orElse(null), name, cpf.masked(), now);
        sessions.save(session);
        metrics.representative(outcome);
        // só "confirmado" ou não: nada do CPF, do nome nem do motivo
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_REPRESENTATIVE_DECLARED)
                .creditor(c.config().getCreditorId()).debtor(companyId).channel(PortalSelfServiceService.CHANNEL)
                .reference("portal_session", session.getId()).attr("confirmed", match.isPresent()));
        return match.isPresent();
    }

    /** Representante confirmado na sessão que ainda pode negociar hoje (vazio = aceite por atendimento). */
    Optional<RepresentativeView> confirmed(PortalSession session) {
        if (session.getRepresentativeId() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(representatives.get(session.getDebtorId(), session.getRepresentativeId()))
                    .filter(RepresentativeView::negotiatesToday);
        } catch (DomainException e) {
            return Optional.empty();
        }
    }

    boolean hasNegotiators(UUID companyId) {
        return representatives.hasNegotiators(companyId);
    }

    private static Document cpf(String raw) {
        try {
            Document document = Document.parse(raw);
            if (document.type() == Document.DocumentType.CPF) {
                return document;
            }
        } catch (BusinessRuleException e) {
            // mesma resposta abaixo
        }
        throw new BusinessRuleException("portal.representative_cpf_invalid", "Informe um CPF válido");
    }

    private ThrottlePolicy.Limits limits(int maxFailures) {
        PortalProperties.Representative r = properties.representative();
        // a confirmação não tem "pedidos": o limite de pedidos não se aplica (só falhas)
        return new ThrottlePolicy.Limits(maxFailures, Integer.MAX_VALUE, r.window(), r.baseLock(), r.maxLock());
    }

    private void lockApplied(Current c, KeyType key, Instant until) {
        accessLogger.log(AccessEvent.forDebtor(AccessEventType.PORTAL_LOCKED, c.config().getTenantId(),
                c.session().getDebtorId(), null, Map.of("portal", c.config().getSlug(), "key", key.name(),
                        "until", until.toString())));
        metrics.locked(key.name(), "APPLIED");
    }
}
