package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PixWebhookUseCases;
import br.com.gestorcobranca.payment.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.payment.application.port.out.WebhookRouteDirectory;
import br.com.gestorcobranca.payment.application.port.out.WebhookRouteDirectory.Route;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.model.WebhookAuthMode;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import br.com.gestorcobranca.payment.domain.service.PixIdentifiers;
import br.com.gestorcobranca.payment.domain.service.WebhookAuthentication;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook Pix no padrão da API Pix do BACEN ({@code POST <webhookUrl>/pix} com {@code {"pix":[...]}}).
 * Ordem de proteção: rota (tenant e conta, chave aleatória de 256 bits) → autenticação antes de
 * interpretar o corpo (mTLS com certificado do PSP fixado na conta, ou HMAC do corpo bruto) → recibo do
 * corpo (reentrega idêntica não tem efeito) → notificações com horário fora da janela de replay
 * descartadas → confirmação de cada Pix por consulta ao PSP (job {@code payment.pix_sync}, idempotente
 * pelo {@code endToEndId}). A notificação nunca é tratada como prova de pagamento por si só. Toda recusa
 * gera {@code WEBHOOK_REJECTED} no log de acesso.
 */
@Service
class PixWebhookService implements PixWebhookUseCases {

    static final String PROVIDER = "pix";
    private static final Logger LOG = LoggerFactory.getLogger(PixWebhookService.class);

    private final WebhookRouteDirectory routes;
    private final WebhookReceiptLog receipts;
    private final PixAccountRepository accounts;
    private final PixCredentialCodec credentials;
    private final JobQueue jobs;
    private final TenantExecution tenantExecution;
    private final AccessLogger accessLogger;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final JsonCodec json;
    private final Clock clock;

    PixWebhookService(WebhookRouteDirectory routes, WebhookReceiptLog receipts, PixAccountRepository accounts,
                      PixCredentialCodec credentials, JobQueue jobs, TenantExecution tenantExecution,
                      AccessLogger accessLogger, PaymentMetrics metrics, PaymentProperties properties, JsonCodec json,
                      Clock clock) {
        this.routes = routes;
        this.receipts = receipts;
        this.accounts = accounts;
        this.credentials = credentials;
        this.jobs = jobs;
        this.tenantExecution = tenantExecution;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Outcome receive(String routingKey, String signatureHeader, String clientCertificate, byte[] body,
                           String clientIp) {
        Optional<Route> route = routingKey == null || routingKey.length() > 64 ? Optional.empty()
                : routes.resolve(routingKey).filter(Route::active);
        if (route.isEmpty()) {
            reject(null, "unknown_route", clientIp);
            metrics.webhookRequest(PROVIDER, Outcome.UNKNOWN_ROUTE.name());
            return Outcome.UNKNOWN_ROUTE;
        }
        UUID tenantId = route.get().tenantId();
        Outcome outcome = tenantExecution.callInTenant(tenantId, "payment.pix_webhook", () -> {
            Optional<PixAccount> account = accounts.findById(route.get().pixAccountId()).filter(PixAccount::isActive);
            if (account.isEmpty() || !authenticated(account.get(), signatureHeader, clientCertificate, body)) {
                return Outcome.UNAUTHENTICATED;
            }
            if (!receipts.registerIfFirst(account.get().getId(), sha256(body), Instant.now(clock))) {
                return Outcome.DUPLICATE;
            }
            JsonNode root;
            try {
                root = json.mapper().readTree(body);
            } catch (RuntimeException e) {
                LOG.atWarn().addKeyValue("provider", PROVIDER).log("Notificação Pix autenticada com corpo ilegível");
                return Outcome.MALFORMED;
            }
            JsonNode pix = root.path("pix");
            if (!pix.isArray()) {
                return Outcome.MALFORMED;
            }
            schedule(account.get(), pix);
            return Outcome.ACCEPTED;
        });
        if (outcome == Outcome.UNAUTHENTICATED) {
            reject(tenantId, "unauthenticated", clientIp);
        }
        metrics.webhookRequest(PROVIDER, outcome.name());
        return outcome;
    }

    private boolean authenticated(PixAccount account, String signature, String clientCertificate, byte[] body) {
        if (account.getWebhookAuth() == WebhookAuthMode.MTLS) {
            return WebhookAuthentication.validClientCertificate(clientCertificate, account.webhookFingerprints(),
                    Instant.now(clock));
        }
        String secret = credentials.decode(account.getCredentials()).get(PixCredentialCodec.WEBHOOK_SECRET);
        return WebhookAuthentication.validHmac(secret, signature, body);
    }

    private void schedule(PixAccount account, JsonNode pix) {
        Instant now = Instant.now(clock);
        Instant oldest = now.minus(properties.webhook().maxEventAge());
        Instant newest = now.plus(properties.webhook().maxClockSkew());
        for (JsonNode item : pix) {
            String endToEndId = item.path("endToEndId").asString("");
            Instant at = instant(item.path("horario").asString(""));
            if (!PixIdentifiers.validEndToEndId(endToEndId) || at == null) {
                metrics.webhookEvent("invalid");
                continue;
            }
            if (at.isBefore(oldest) || at.isAfter(newest)) {
                metrics.webhookEvent("stale");
                LOG.atInfo().addKeyValue("pixAccountId", account.getId())
                        .log("Notificação Pix fora da janela de replay descartada");
                continue;
            }
            PixSyncJob.enqueue(jobs, account.getId(), endToEndId);
            metrics.webhookEvent("scheduled");
        }
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void reject(UUID tenantId, String reason, String clientIp) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("provider", PROVIDER);
        details.put("reason", reason);
        if (clientIp != null) {
            details.put("sourceIp", clientIp);
        }
        accessLogger.log(AccessEvent.anonymous(AccessEventType.WEBHOOK_REJECTED, tenantId, null, details));
        LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("reason", reason).addKeyValue("tenantId", tenantId)
                .log("Webhook Pix recusado");
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
