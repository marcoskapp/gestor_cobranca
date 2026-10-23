package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.in.ProviderCallbackUseCases;
import br.com.gestorcobranca.communication.application.port.in.WebhookUseCases;
import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter;
import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.InboundBatch;
import br.com.gestorcobranca.communication.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.communication.application.port.out.WebhookRouteDirectory;
import br.com.gestorcobranca.communication.application.port.out.WebhookRouteDirectory.Route;
import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Webhooks de provedores. Ordem de proteção: rota (tenant e conta) → assinatura do corpo bruto com
 * o segredo da conta (antes de qualquer interpretação) → recibo do corpo (reentrega idêntica não
 * tem efeito) → eventos fora da janela de replay descartados → processamento idempotente por id do
 * provedor. Toda recusa gera {@code WEBHOOK_REJECTED} no log de acesso. Limite de tamanho e de taxa
 * são aplicados antes, no adaptador web.
 */
@Service
class WebhookService implements WebhookUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookService.class);
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_\\-]{1,200}");

    private final WebhookRouteDirectory routes;
    private final WebhookReceiptLog receipts;
    private final ChannelAccountRepository accounts;
    private final CredentialCodec credentialCodec;
    private final ProviderCallbackUseCases callbacks;
    private final Map<ProviderType, ProviderWebhookAdapter> adapters;
    private final TenantExecution tenantExecution;
    private final AccessLogger accessLogger;
    private final CommunicationMetrics metrics;
    private final CommunicationProperties properties;
    private final Clock clock;

    WebhookService(WebhookRouteDirectory routes, WebhookReceiptLog receipts, ChannelAccountRepository accounts,
                   CredentialCodec credentialCodec, ProviderCallbackUseCases callbacks,
                   List<ProviderWebhookAdapter> adapters, TenantExecution tenantExecution, AccessLogger accessLogger,
                   CommunicationMetrics metrics, CommunicationProperties properties, Clock clock) {
        this.routes = routes;
        this.receipts = receipts;
        this.accounts = accounts;
        this.credentialCodec = credentialCodec;
        this.callbacks = callbacks;
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(ProviderWebhookAdapter::provider, Function.identity()));
        this.tenantExecution = tenantExecution;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<String> verifySubscription(String provider, String routingKey, String mode, String verifyToken,
                                               String challenge, String clientIp) {
        Optional<ProviderType> type = providerOf(provider);
        Optional<Route> route = type.flatMap(t -> route(t, routingKey));
        if (route.isEmpty()) {
            reject(null, provider, "unknown_route", clientIp);
            metrics.webhookRequest(provider, "UNKNOWN_ROUTE");
            return Optional.empty();
        }
        if (!"subscribe".equals(mode) || challenge == null || !CHALLENGE.matcher(challenge).matches()) {
            reject(route.get().tenantId(), provider, "verify_invalid_request", clientIp);
            metrics.webhookRequest(provider, "VERIFY_REJECTED");
            return Optional.empty();
        }
        ProviderWebhookAdapter adapter = adapters.get(type.get());
        boolean valid = tenantExecution.callInTenant(route.get().tenantId(), "communication.webhook_verify", () ->
                account(route.get()).map(a -> adapter.validVerifyToken(credentialCodec.decode(a.getCredentials()), verifyToken))
                        .orElse(false));
        if (!valid) {
            reject(route.get().tenantId(), provider, "verify_token_invalid", clientIp);
            metrics.webhookRequest(provider, "VERIFY_REJECTED");
            return Optional.empty();
        }
        metrics.webhookRequest(provider, "VERIFIED");
        LOG.atInfo().addKeyValue("provider", provider).addKeyValue("tenantId", route.get().tenantId())
                .log("Webhook verificado pelo provedor");
        return Optional.of(challenge);
    }

    @Override
    public Outcome receive(String provider, String routingKey, String signatureHeader, byte[] body, String clientIp) {
        Optional<ProviderType> type = providerOf(provider);
        Optional<Route> route = type.flatMap(t -> route(t, routingKey));
        if (route.isEmpty()) {
            reject(null, provider, "unknown_route", clientIp);
            metrics.webhookRequest(provider, Outcome.UNKNOWN_ROUTE.name());
            return Outcome.UNKNOWN_ROUTE;
        }
        ProviderWebhookAdapter adapter = adapters.get(type.get());
        UUID tenantId = route.get().tenantId();
        Outcome outcome = tenantExecution.callInTenant(tenantId, "communication.webhook", () -> {
            Optional<ChannelAccount> account = account(route.get());
            if (account.isEmpty()
                    || !adapter.validSignature(credentialCodec.decode(account.get().getCredentials()), signatureHeader, body)) {
                return Outcome.INVALID_SIGNATURE;
            }
            if (!receipts.registerIfFirst(account.get().getId(), sha256(body), Instant.now(clock))) {
                return Outcome.DUPLICATE;
            }
            InboundBatch batch;
            try {
                batch = adapter.parse(body);
            } catch (IllegalArgumentException e) {
                LOG.atWarn().addKeyValue("provider", provider).log("Webhook assinado com corpo ilegível");
                return Outcome.MALFORMED;
            }
            process(account.get(), type.get(), batch);
            return Outcome.ACCEPTED;
        });
        if (outcome == Outcome.INVALID_SIGNATURE) {
            reject(tenantId, provider, "invalid_signature", clientIp);
        }
        metrics.webhookRequest(provider, outcome.name());
        return outcome;
    }

    private void process(ChannelAccount account, ProviderType provider, InboundBatch batch) {
        Instant now = Instant.now(clock);
        Instant oldest = now.minus(properties.webhook().maxEventAge());
        Instant newest = now.plus(properties.webhook().maxClockSkew());
        for (ProviderWebhookAdapter.InboundMessage message : batch.messages()) {
            if (outsideWindow(message.timestamp(), oldest, newest)) {
                metrics.webhookEvent(provider.name(), "message", "stale");
                continue;
            }
            callbacks.inbound(account.getId(), message);
        }
        for (ProviderWebhookAdapter.StatusUpdate status : batch.statuses()) {
            if (outsideWindow(status.timestamp(), oldest, newest)) {
                metrics.webhookEvent(provider.name(), "status", "stale");
                continue;
            }
            callbacks.status(account.getId(), status);
        }
    }

    private static boolean outsideWindow(Instant at, Instant oldest, Instant newest) {
        return at == null || at.isBefore(oldest) || at.isAfter(newest);
    }

    private Optional<Route> route(ProviderType provider, String routingKey) {
        if (routingKey == null || routingKey.length() > 64) {
            return Optional.empty();
        }
        return routes.resolve(routingKey).filter(Route::active).filter(r -> provider.name().equals(r.provider()));
    }

    private Optional<ChannelAccount> account(Route route) {
        return accounts.findById(route.channelAccountId()).filter(ChannelAccount::isActive);
    }

    private Optional<ProviderType> providerOf(String provider) {
        if ("whatsapp".equals(provider == null ? null : provider.toLowerCase(Locale.ROOT))) {
            return Optional.of(ProviderType.WHATSAPP_CLOUD).filter(adapters::containsKey);
        }
        return Optional.empty();
    }

    private void reject(UUID tenantId, String provider, String reason, String clientIp) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("provider", provider == null ? "unknown" : provider);
        details.put("reason", reason);
        if (clientIp != null) {
            details.put("sourceIp", clientIp);
        }
        accessLogger.log(AccessEvent.anonymous(AccessEventType.WEBHOOK_REJECTED, tenantId, null, details));
        LOG.atWarn().addKeyValue("provider", provider).addKeyValue("reason", reason).addKeyValue("tenantId", tenantId)
                .log("Webhook recusado");
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
