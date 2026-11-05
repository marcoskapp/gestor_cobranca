package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.InboundMailProperties;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases;
import br.com.gestorcobranca.collection.application.port.out.DisputeReplyRoutes;
import br.com.gestorcobranca.collection.application.port.out.InboundMailParser;
import br.com.gestorcobranca.collection.application.port.out.InboundMailParser.ParsedMail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entrada dos e-mails ao subdomínio de respostas: encontra o endereço de resposta do pedido entre os destinatários,
 * resolve a rota (tabela global, só o hash do token) e registra no tenant do pedido. Sem rota, o e-mail fica no
 * registro técnico global ({@code inbound_mail_unrouted}) com a localização do original no provedor — nunca é
 * descartado em silêncio. Reentregas (mesmo id do provedor) não duplicam.
 */
@Service
class InboundMailService implements InboundMailUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(InboundMailService.class);

    private final DisputeReplyRoutes routes;
    private final InboundMailParser parser;
    private final DisputeMailReplyIntake intake;
    private final TenantExecution tenantExecution;
    private final CollectionMetrics metrics;
    private final InboundMailProperties properties;
    private final Clock clock;
    private final Pattern address;

    InboundMailService(DisputeReplyRoutes routes, InboundMailParser parser, DisputeMailReplyIntake intake,
                       TenantExecution tenantExecution, CollectionMetrics metrics, InboundMailProperties properties,
                       Clock clock) {
        this.routes = routes;
        this.parser = parser;
        this.intake = intake;
        this.tenantExecution = tenantExecution;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.address = Pattern.compile("^" + Pattern.quote(properties.localPart()) + "\\+([a-z2-7]{26})@"
                + Pattern.quote(properties.domain() == null ? "invalid.invalid" : properties.domain()) + "$");
    }

    @Override
    public Outcome receive(InboundMessage message) {
        Guard.require(message != null && message.raw() != null && message.providerMessageId() != null,
                "inbound_mail.invalid", "E-mail recebido inválido");
        Guard.requireState(properties.enabled(), "inbound_mail.disabled", "Leitura de e-mail desligada");
        Instant receivedAt = message.receivedAt() == null ? Instant.now(clock) : message.receivedAt();
        ParsedMail parsed = parser.parse(message.raw(), properties.maxTextChars(), properties.maxAttachments());
        List<String> candidates = message.envelopeRecipients().isEmpty() ? parsed.recipients()
                : message.envelopeRecipients();
        Optional<String> token = candidates.stream().map(r -> r.trim().toLowerCase(Locale.ROOT)).map(address::matcher)
                .filter(Matcher::matches).map(m -> m.group(1)).findFirst();
        if (token.isEmpty()) {
            return unrouted(message, receivedAt, "NO_REPLY_ADDRESS");
        }
        Optional<DisputeReplyRoutes.Route> route = routes.find(DisputeCreditorChannel.sha256(token.get()));
        if (route.isEmpty()) {
            return unrouted(message, receivedAt, "UNKNOWN_TOKEN");
        }
        InboundMessage normalized = new InboundMessage(message.providerMessageId(), message.raw(),
                message.envelopeRecipients(), message.verdicts(), receivedAt, message.providerLocator());
        try {
            return tenantExecution.callInTenant(route.get().tenantId(), "collection.inbound-mail",
                    () -> intake.register(route.get(), normalized, parsed));
        } catch (DataIntegrityViolationException e) {
            // reentrega concorrente do mesmo e-mail (índice único por id do provedor)
            metrics.disputeMailReceived("duplicate", "none");
            return Outcome.DUPLICATE;
        }
    }

    private Outcome unrouted(InboundMessage message, Instant receivedAt, String reason) {
        boolean recorded = routes.recordUnrouted(message.providerMessageId(), receivedAt, message.providerLocator(),
                message.raw().length, reason);
        metrics.disputeMailReceived(recorded ? "unrouted" : "duplicate", reason.toLowerCase(Locale.ROOT));
        LOG.atWarn().addKeyValue("providerMessageId", message.providerMessageId()).addKeyValue("reason", reason)
                .log("E-mail ao subdomínio de respostas sem pedido correspondente");
        return recorded ? Outcome.UNROUTED : Outcome.DUPLICATE;
    }
}
