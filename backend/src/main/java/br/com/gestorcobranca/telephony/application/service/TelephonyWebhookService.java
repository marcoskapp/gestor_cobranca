package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.ProviderEventUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter;
import br.com.gestorcobranca.telephony.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.telephony.application.port.out.WebhookRouteDirectory;
import br.com.gestorcobranca.telephony.application.port.out.WebhookRouteDirectory.Route;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Webhooks do provedor de telefonia (mesmo padrão do communication). Ordem de proteção: rota
 * (tenant e conta) → assinatura do corpo bruto com o segredo da conta, antes de qualquer
 * interpretação (o adaptador também recusa assinatura com instante fora da tolerância) → recibo
 * do corpo (reentrega idêntica sem efeito) → eventos fora da janela de replay descartados →
 * aplicação idempotente pelo id do evento. Toda recusa gera {@code WEBHOOK_REJECTED} no log de
 * acesso. Limites de tamanho e de taxa são aplicados antes, no adaptador web.
 */
@Service
class TelephonyWebhookService implements TelephonyWebhookUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(TelephonyWebhookService.class);

    private final WebhookRouteDirectory routes;
    private final WebhookReceiptLog receipts;
    private final TelephonyAccountRepository accounts;
    private final CredentialCodec credentialCodec;
    private final ProviderEventUseCases events;
    private final Map<ProviderType, TelephonyWebhookAdapter> adapters;
    private final TenantExecution tenantExecution;
    private final AccessLogger accessLogger;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    TelephonyWebhookService(WebhookRouteDirectory routes, WebhookReceiptLog receipts, TelephonyAccountRepository accounts,
                            CredentialCodec credentialCodec, ProviderEventUseCases events,
                            List<TelephonyWebhookAdapter> adapters, TenantExecution tenantExecution,
                            AccessLogger accessLogger, TelephonyMetrics metrics, TelephonyProperties properties,
                            Clock clock) {
        this.routes = routes;
        this.receipts = receipts;
        this.accounts = accounts;
        this.credentialCodec = credentialCodec;
        this.events = events;
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(TelephonyWebhookAdapter::provider, Function.identity()));
        this.tenantExecution = tenantExecution;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Outcome receive(String routingKey, Map<String, String> headers, byte[] body, String clientIp) {
        Optional<Route> route = route(routingKey);
        if (route.isEmpty()) {
            reject(null, "unknown", "unknown_route", clientIp);
            metrics.webhookRequest("unknown", Outcome.UNKNOWN_ROUTE.name());
            return Outcome.UNKNOWN_ROUTE;
        }
        ProviderType provider = ProviderType.valueOf(route.get().provider());
        TelephonyWebhookAdapter adapter = adapters.get(provider);
        UUID tenantId = route.get().tenantId();
        Outcome outcome = tenantExecution.callInTenant(tenantId, "telephony.webhook", () -> {
            Instant now = Instant.now(clock);
            Optional<TelephonyAccount> account = accounts.findById(route.get().accountId()).filter(TelephonyAccount::isActive);
            if (account.isEmpty()
                    || !adapter.validSignature(credentialCodec.decode(account.get().getCredentials()), headers, body, now)) {
                return Outcome.INVALID_SIGNATURE;
            }
            if (!receipts.registerBodyIfFirst(account.get().getId(), sha256(body), now)) {
                return Outcome.DUPLICATE;
            }
            List<ProviderCallEvent> parsed;
            try {
                parsed = adapter.parse(body);
            } catch (IllegalArgumentException e) {
                LOG.atWarn().addKeyValue("provider", provider).log("Webhook de telefonia assinado com corpo ilegível");
                return Outcome.MALFORMED;
            }
            Instant oldest = now.minus(properties.webhook().maxEventAge());
            Instant newest = now.plus(properties.webhook().maxClockSkew());
            for (ProviderCallEvent event : parsed) {
                if (event.occurredAt() == null || event.occurredAt().isBefore(oldest) || event.occurredAt().isAfter(newest)) {
                    metrics.webhookEvent(provider.name(), event.type() == null ? "unknown" : event.type().name(), "stale");
                    continue;
                }
                events.apply(account.get().getId(), event);
            }
            return Outcome.ACCEPTED;
        });
        if (outcome == Outcome.INVALID_SIGNATURE) {
            reject(tenantId, provider.name(), "invalid_signature", clientIp);
        }
        metrics.webhookRequest(provider.name(), outcome.name());
        return outcome;
    }

    private Optional<Route> route(String routingKey) {
        if (routingKey == null || routingKey.length() > 64) {
            return Optional.empty();
        }
        return routes.resolve(routingKey).filter(Route::active)
                .filter(r -> adapters.keySet().stream().anyMatch(p -> p.name().equals(r.provider())));
    }

    private void reject(UUID tenantId, String provider, String reason, String clientIp) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("provider", provider);
        details.put("channel", "telephony");
        details.put("reason", reason);
        if (clientIp != null) {
            details.put("sourceIp", clientIp);
        }
        accessLogger.log(AccessEvent.anonymous(AccessEventType.WEBHOOK_REJECTED, tenantId, null, details));
        LOG.atWarn().addKeyValue("provider", provider).addKeyValue("reason", reason).addKeyValue("tenantId", tenantId)
                .log("Webhook de telefonia recusado");
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
