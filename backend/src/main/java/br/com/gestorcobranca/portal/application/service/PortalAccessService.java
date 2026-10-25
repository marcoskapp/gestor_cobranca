package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases;
import br.com.gestorcobranca.portal.application.port.out.AccessThrottleStore;
import br.com.gestorcobranca.portal.application.service.PortalSites.Site;
import br.com.gestorcobranca.portal.domain.model.AccessChallenge;
import br.com.gestorcobranca.portal.domain.model.AccessLink;
import br.com.gestorcobranca.portal.domain.model.AccessMethod;
import br.com.gestorcobranca.portal.domain.model.KnowledgeChallenge;
import br.com.gestorcobranca.portal.domain.model.PortalChannel;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.repository.AccessChallengeRepository;
import br.com.gestorcobranca.portal.domain.repository.AccessLinkRepository;
import br.com.gestorcobranca.portal.domain.service.AccessSecrets;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.KeyType;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.State;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AuthenticationFailedException;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Acesso público ao portal. Cada etapa roda no tenant do portal (resolvido pelo endereço) como
 * sistema, em transação própria: falhas e bloqueios são <strong>gravados</strong> antes da resposta
 * de erro. Regras anti-enumeração:
 * <ul>
 *   <li>pedido de código: mesma resposta (e tempo mínimo) para documento inexistente, anonimizado
 *       ou sem dívida no portal — nesses casos o desafio é fictício e nada é enviado;</li>
 *   <li>verificação: sempre {@code portal.access_invalid}, sem dizer se o documento existe, se o código
 *       ou o dado de conhecimento estavam errados ou se o desafio expirou;</li>
 *   <li>o bloqueio progressivo vale igualmente para documento inexistente.</li>
 * </ul>
 * O código só existe em claro na mensagem enviada pelo communication (que passa pelo porteiro); o
 * desafio guarda o HMAC.
 */
@Service
class PortalAccessService implements PortalAccessUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PortalAccessService.class);
    static final String INVALID_CODE = "portal.access_invalid";
    static final String INVALID_MESSAGE = "Não foi possível confirmar seu acesso. Confira os dados e tente de novo "
            + "ou peça um novo código.";

    private final PortalSites sites;
    private final PortalDebts debts;
    private final KnowledgeVerifier knowledge;
    private final SessionOpener sessionOpener;
    private final AccessChallengeRepository challenges;
    private final AccessLinkRepository links;
    private final AccessThrottleStore throttle;
    private final MessageUseCases messages;
    private final FileStorage fileStorage;
    private final BlindIndex blindIndex;
    private final TenantExecution tenantExecution;
    private final AccessLogger accessLogger;
    private final PortalProperties properties;
    private final PortalMetrics metrics;
    private final Clock clock;
    private final Executor dispatchExecutor;

    PortalAccessService(PortalSites sites, PortalDebts debts, KnowledgeVerifier knowledge, SessionOpener sessionOpener,
                  AccessChallengeRepository challenges, AccessLinkRepository links, AccessThrottleStore throttle,
                  MessageUseCases messages, FileStorage fileStorage, BlindIndex blindIndex,
                  TenantExecution tenantExecution, AccessLogger accessLogger, PortalProperties properties,
                  PortalMetrics metrics, Clock clock,
                  @Qualifier("portalCodeDispatchExecutor") Executor dispatchExecutor) {
        this.sites = sites;
        this.debts = debts;
        this.knowledge = knowledge;
        this.sessionOpener = sessionOpener;
        this.challenges = challenges;
        this.links = links;
        this.throttle = throttle;
        this.messages = messages;
        this.fileStorage = fileStorage;
        this.blindIndex = blindIndex;
        this.tenantExecution = tenantExecution;
        this.accessLogger = accessLogger;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.dispatchExecutor = dispatchExecutor;
    }

    // ------------------------------------------------------------------ marca pública

    @Override
    public SiteView site(String slug) {
        Site site = sites.resolve(slug);
        return tenantExecution.callInTenant(site.tenantId(), "portal.site", () -> {
            PortalConfiguration c = sites.enabled(site);
            return new SiteView(c.getSlug(), c.getDisplayName(), c.getPrimaryColor(), c.getAccentColor(),
                    c.getLogoFileKey() != null, c.getLogoSha256() == null ? null : c.getLogoSha256().substring(0, 12),
                    c.getOtpChannels().stream().map(Enum::name).toList(), c.getKnowledgeChallenge().name(),
                    c.getSupportText(), c.getPrivacyNoticeVersion(), c.getPrivacyNoticeText());
        });
    }

    @Override
    public LogoContent logo(String slug) {
        Site site = sites.resolve(slug);
        return tenantExecution.callInTenant(site.tenantId(), "portal.logo", () -> {
            PortalConfiguration c = sites.enabled(site);
            if (c.getLogoFileKey() == null) {
                throw new ResourceNotFoundException("portal_logo", slug);
            }
            return new LogoContent(c.getLogoContentType(), c.getLogoSha256(), fileStorage.open(c.getLogoFileKey()));
        });
    }

    // ------------------------------------------------------------------ código (OTP)

    private record Pending(ChallengeView view, UUID challengeId, UUID debtorId, UUID creditorId, PortalChannel channel,
                           String code, Instant lockedUntil) {
    }

    @Override
    public ChallengeView requestCode(String slug, RequestCodeCommand command, ClientInfo client) {
        long started = System.nanoTime();
        try {
            return doRequestCode(slug, command, client);
        } finally {
            equalize(started);
        }
    }

    private ChallengeView doRequestCode(String slug, RequestCodeCommand command, ClientInfo client) {
        Site site = sites.resolve(slug);
        Guard.require(command != null, "portal.request_required", "Informe o documento");
        Document document = Document.parse(command.document());
        PortalChannel channel = channel(command.channel());
        Pending pending = tenantExecution.callInTenant(site.tenantId(), "portal.access_code", () -> {
            PortalConfiguration config = sites.enabled(site);
            Guard.require(config.getOtpChannels().contains(channel), "portal.channel_unavailable",
                    "Canal não disponível neste portal");
            Instant now = Instant.now(clock);
            String ipKey = key(site, "portal.ip", ip(client));
            String docKey = key(site, "portal.document", document.number());
            Instant locked = lockedUntil(now, ipKey, docKey);
            if (locked == null) {
                State ipState = throttle.update(KeyType.IP, ipKey, now, s -> ThrottlePolicy.onRequest(s, ipLimits(), now));
                State docState = throttle.update(KeyType.DOCUMENT, docKey, now,
                        s -> ThrottlePolicy.onRequest(s, documentLimits(), now));
                locked = latest(ipState.lockedAt(now) ? ipState.lockedUntil() : null,
                        docState.lockedAt(now) ? docState.lockedUntil() : null);
                if (locked != null) {
                    lockApplied(site, ipState.lockedAt(now) ? "IP" : "DOCUMENT", document.masked(), locked);
                }
            }
            if (locked != null) {
                return new Pending(null, null, null, null, channel, null, locked);
            }
            UUID debtorId = debts.eligibleDebtor(config, document).orElse(null);
            String code = AccessSecrets.newCode();
            Instant expiresAt = now.plus(properties.access().codeTtl());
            AccessChallenge challenge = challenges.save(AccessChallenge.otp(config.getId(), debtorId, channel, docKey,
                    codeHash(site, docKey, code), config.getKnowledgeChallenge(), properties.access().codeMaxAttempts(),
                    expiresAt));
            accessLogger.log(AccessEvent.anonymous(AccessEventType.PORTAL_CODE_REQUESTED, site.tenantId(),
                    document.masked(), Map.of("portal", site.slug(), "channel", channel.name(),
                            "challengeId", challenge.getId().toString(), "match", String.valueOf(debtorId != null))));
            ChallengeView view = new ChallengeView(challenge.getId(), expiresAt, channel.name(),
                    config.getKnowledgeChallenge().name(), true, AccessSecrets.CODE_LENGTH);
            return new Pending(view, challenge.getId(), debtorId, config.getCreditorId(), channel, code, null);
        });
        if (pending.lockedUntil() != null) {
            metrics.locked("ANY", "REFUSED");
            throw new AccessLockedException(pending.lockedUntil());
        }
        if (pending.debtorId() == null) {
            metrics.codeRequested(channel.name(), "DECOY");
        } else {
            // envio fora da requisição: mesmo trabalho e tempo de resposta para documento existente ou não
            try {
                dispatchExecutor.execute(() -> dispatchCode(site, pending));
            } catch (RejectedExecutionException e) {
                metrics.codeRequested(pending.channel().name(), "NOT_DELIVERED");
                LOG.atWarn().addKeyValue("tenantId", site.tenantId()).addKeyValue("challengeId", pending.challengeId())
                        .log("Fila de envio de códigos do portal cheia: código não enviado");
            }
        }
        return pending.view();
    }

    /**
     * Envio do código pelo communication como sistema (o porteiro decide), em transação própria e em
     * thread própria (depois da resposta ao pedido): uma
     * recusa (template ausente, sem contato no canal, canal sem conta) não desfaz o desafio e a resposta
     * ao devedor continua a mesma.
     */
    private void dispatchCode(Site site, Pending p) {
        long minutes = Math.max(1, properties.access().codeTtl().toMinutes());
        try {
            String status = tenantExecution.callInTenant(site.tenantId(), "portal.access_code_send", () -> {
                MessageView message = messages.send(new SendMessageCommand(null, p.debtorId(), p.creditorId(), null,
                        p.channel().name(), null, template(properties.templates().accessCode(), p.channel()),
                        Map.of("codigo", p.code(), "minutos", String.valueOf(minutes)), null, null,
                        "portal-code:" + p.challengeId()));
                challenges.findByIdForUpdate(p.challengeId()).ifPresent(c -> {
                    c.sentAs(message.id());
                    challenges.save(c);
                });
                return message.status();
            });
            metrics.codeRequested(p.channel().name(), "FAILED".equals(status) ? "NOT_DELIVERED" : "SENT");
        } catch (RuntimeException e) {
            metrics.codeRequested(p.channel().name(), "NOT_DELIVERED");
            LOG.atWarn().addKeyValue("tenantId", site.tenantId()).addKeyValue("challengeId", p.challengeId())
                    .addKeyValue("channel", p.channel().name()).addKeyValue("reason", reason(e))
                    .log("Código de acesso ao portal não enviado");
        }
    }

    // ------------------------------------------------------------------ verificação

    private record Outcome(SessionGrant grant, ChallengeView knowledgeStep, Instant lockedUntil, String failure) {

        static Outcome locked(Instant until) {
            return new Outcome(null, null, until, null);
        }

        static Outcome invalid(String reason) {
            return new Outcome(null, null, null, reason);
        }
    }

    @Override
    public SessionGrant verify(String slug, UUID challengeId, VerifyCommand command, ClientInfo client) {
        Site site = sites.resolve(slug);
        String code = command == null ? null : command.code();
        String answer = command == null ? null : command.knowledgeAnswer();
        Outcome outcome = tenantExecution.callInTenant(site.tenantId(), "portal.access_verify", () -> {
            PortalConfiguration config = sites.enabled(site);
            Instant now = Instant.now(clock);
            String ipKey = key(site, "portal.ip", ip(client));
            Instant locked = lockedUntil(now, ipKey, null);
            if (locked != null) {
                return Outcome.locked(locked);
            }
            AccessChallenge challenge = challengeId == null ? null : challenges.findByIdForUpdate(challengeId)
                    .filter(c -> c.getConfigurationId().equals(config.getId())).orElse(null);
            if (challenge == null) {
                return fail(site, ipKey, null, null, null, "unknown_challenge");
            }
            String docKey = challenge.getDocumentHash();
            locked = lockedUntil(now, null, docKey);
            if (locked != null) {
                return Outcome.locked(locked);
            }
            challenge.expireIfDue(now);
            if (!challenge.openAt(now)) {
                challenges.save(challenge);
                return fail(site, ipKey, docKey, challenge, null, "challenge_closed");
            }
            boolean codeOk = challenge.getMethod() == AccessMethod.LINK
                    || (AccessSecrets.isWellFormedCode(code) && challenge.codeMatches(codeHash(site, docKey, code)));
            boolean accepted = codeOk && !challenge.isDecoy()
                    && knowledge.matches(challenge.getKnowledgeChallenge(), config, challenge.getDebtorId(), answer)
                    && debts.hasDebtsInPortal(config, challenge.getDebtorId());
            if (!accepted) {
                challenge.fail(now);
                challenges.save(challenge);
                String reason = challenge.isDecoy() ? "no_match" : !codeOk ? "wrong_code" : "wrong_knowledge";
                return fail(site, ipKey, docKey, challenge, challenge.getDebtorId(), reason);
            }
            challenge.verify(now);
            challenges.save(challenge);
            succeeded(ipKey, docKey, now);
            return new Outcome(sessionOpener.open(config, challenge.getDebtorId(), challenge.getMethod(), client, now),
                    null, null, null);
        });
        return grantOrThrow(outcome, "CHALLENGE");
    }

    // ------------------------------------------------------------------ link de uso único

    @Override
    public LinkResult redeemLink(String slug, String token, ClientInfo client) {
        Site site = sites.resolve(slug);
        Outcome outcome = tenantExecution.callInTenant(site.tenantId(), "portal.access_link", () -> {
            PortalConfiguration config = sites.enabled(site);
            Instant now = Instant.now(clock);
            String ipKey = key(site, "portal.ip", ip(client));
            Instant locked = lockedUntil(now, ipKey, null);
            if (locked != null) {
                return Outcome.locked(locked);
            }
            AccessLink link = AccessSecrets.isWellFormedLinkToken(token)
                    ? links.findByTokenHashForUpdate(AccessSecrets.sha256(token))
                    .filter(l -> l.getConfigurationId().equals(config.getId())).orElse(null) : null;
            if (link == null || !link.usableAt(now)) {
                return fail(site, ipKey, null, null, link == null ? null : link.getDebtorId(),
                        link == null ? "unknown_link" : "link_unusable");
            }
            link.use(now);
            links.save(link);
            if (!debts.hasDebtsInPortal(config, link.getDebtorId())) {
                return fail(site, ipKey, null, null, link.getDebtorId(), "no_debts");
            }
            if (config.getKnowledgeChallenge() != KnowledgeChallenge.NONE) {
                Instant expiresAt = now.plus(properties.access().knowledgeTtl());
                AccessChallenge step = challenges.save(AccessChallenge.afterLink(config.getId(), link.getDebtorId(),
                        link.getId(), config.getKnowledgeChallenge(), properties.access().codeMaxAttempts(), expiresAt));
                metrics.login(AccessMethod.LINK.name(), "KNOWLEDGE_REQUIRED");
                return new Outcome(null, new ChallengeView(step.getId(), expiresAt, link.getChannel().name(),
                        config.getKnowledgeChallenge().name(), false, 0), null, null);
            }
            succeeded(ipKey, null, now);
            return new Outcome(sessionOpener.open(config, link.getDebtorId(), AccessMethod.LINK, client, now), null,
                    null, null);
        });
        if (outcome.knowledgeStep() != null) {
            return new LinkResult(null, outcome.knowledgeStep());
        }
        return new LinkResult(grantOrThrow(outcome, AccessMethod.LINK.name()), null);
    }

    // ------------------------------------------------------------------ apoio

    private SessionGrant grantOrThrow(Outcome outcome, String method) {
        if (outcome.lockedUntil() != null) {
            metrics.locked("ANY", "REFUSED");
            throw new AccessLockedException(outcome.lockedUntil());
        }
        if (outcome.grant() == null) {
            metrics.login(method, "INVALID");
            throw new AuthenticationFailedException(INVALID_CODE, INVALID_MESSAGE);
        }
        return outcome.grant();
    }

    /** Falha registrada (IP e, se houver, documento) e devolvida como resultado — a transação confirma. */
    private Outcome fail(Site site, String ipKey, String docKey, AccessChallenge challenge, UUID debtorId, String reason) {
        Instant now = Instant.now(clock);
        State ipState = throttle.update(KeyType.IP, ipKey, now, s -> ThrottlePolicy.onFailure(s, ipLimits(), now));
        State docState = docKey == null ? null : throttle.update(KeyType.DOCUMENT, docKey, now,
                s -> ThrottlePolicy.onFailure(s, documentLimits(), now));
        Map<String, String> details = new HashMap<>();
        details.put("portal", site.slug());
        details.put("reason", reason);
        if (challenge != null) {
            details.put("challengeId", challenge.getId().toString());
            details.put("attempts", String.valueOf(challenge.getAttempts()));
        }
        accessLogger.log(debtorId == null
                ? AccessEvent.anonymous(AccessEventType.PORTAL_LOGIN_FAILURE, site.tenantId(), null, details)
                : AccessEvent.forDebtor(AccessEventType.PORTAL_LOGIN_FAILURE, site.tenantId(), debtorId, null, details));
        if (ipState.lockedAt(now)) {
            lockApplied(site, "IP", null, ipState.lockedUntil());
        }
        if (docState != null && docState.lockedAt(now)) {
            lockApplied(site, "DOCUMENT", null, docState.lockedUntil());
        }
        return Outcome.invalid(reason);
    }

    private void succeeded(String ipKey, String docKey, Instant now) {
        throttle.update(KeyType.IP, ipKey, now, s -> ThrottlePolicy.onSuccess(s, now, false));
        if (docKey != null) {
            throttle.update(KeyType.DOCUMENT, docKey, now, s -> ThrottlePolicy.onSuccess(s, now, true));
        }
    }

    private void lockApplied(Site site, String key, String maskedDocument, Instant until) {
        accessLogger.log(AccessEvent.anonymous(AccessEventType.PORTAL_LOCKED, site.tenantId(), maskedDocument,
                Map.of("portal", site.slug(), "key", key, "until", until.toString())));
        metrics.locked(key, "APPLIED");
    }

    /** Fim do bloqueio vigente mais distante entre as chaves informadas (nulo = livre). */
    private Instant lockedUntil(Instant now, String ipKey, String docKey) {
        Instant ip = ipKey == null ? null : lockOf(throttle.current(KeyType.IP, ipKey, now), now);
        Instant doc = docKey == null ? null : lockOf(throttle.current(KeyType.DOCUMENT, docKey, now), now);
        return latest(ip, doc);
    }

    private static Instant lockOf(State s, Instant now) {
        return s.lockedAt(now) ? s.lockedUntil() : null;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private ThrottlePolicy.Limits ipLimits() {
        PortalProperties.Access a = properties.access();
        return new ThrottlePolicy.Limits(a.ipMaxFailures(), a.ipMaxRequests(), a.window(), a.baseLock(), a.maxLock());
    }

    private ThrottlePolicy.Limits documentLimits() {
        PortalProperties.Access a = properties.access();
        return new ThrottlePolicy.Limits(a.documentMaxFailures(), a.documentMaxRequests(), a.window(), a.baseLock(),
                a.maxLock());
    }

    private String key(Site site, String purpose, String value) {
        return blindIndex.of(site.tenantId(), purpose, value);
    }

    /** HMAC do código (por assessoria e documento): o banco nunca tem o código em claro. */
    private String codeHash(Site site, String documentKey, String code) {
        return blindIndex.of(site.tenantId(), "portal.otp", documentKey + ":" + code);
    }

    private static String ip(ClientInfo client) {
        return client == null || client.ip() == null || client.ip().isBlank() ? "unknown" : client.ip();
    }

    static PortalChannel channel(String value) {
        try {
            return PortalChannel.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("portal.channel_invalid", "Canal inválido: use WHATSAPP, SMS ou EMAIL");
        }
    }

    static String template(String prefix, PortalChannel channel) {
        return prefix + "_" + channel.name();
    }

    static String reason(RuntimeException e) {
        return e instanceof DomainException d ? d.code() : e.getClass().getSimpleName();
    }

    /** Tempo mínimo de resposta do pedido de código (documento existente × inexistente). */
    private void equalize(long startedNanos) {
        Duration min = properties.access().minResponseTime();
        long remaining = min.toNanos() - (System.nanoTime() - startedNanos);
        if (remaining > 0) {
            try {
                Thread.sleep(Duration.ofNanos(remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
