package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.domain.model.ServiceRequest;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestStatus;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.communication.HandoffRequested;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Encaminha o pedido do portal à fila humana ({@code portal.service_request_dispatch}):
 * <ol>
 *   <li>mensagem de confirmação ao devedor pelo canal escolhido (template
 *       {@code PORTAL_ATENDIMENTO_<CANAL>}, variável {@code protocolo}) — abre ou reutiliza a conversa e
 *       passa pelo porteiro do compliance;</li>
 *   <li>{@code HandoffRequested} para essa conversa (motivo DEBTOR_REQUESTED_HUMAN, DISPUTE ou
 *       PORTAL_NEGOTIATION; resumo sem dados pessoais) — o communication a coloca na fila e devolve o caso
 *       aos humanos.</li>
 * </ol>
 * O envio roda em transação própria: sem contato, canal ou template, o pedido fica
 * {@code UNDELIVERED} (visível na lista de pedidos do portal) em vez de falhar o job.
 */
@Component
class ServiceRequestDispatchJob implements JobHandler<ServiceRequestDispatchJob.Payload> {

    static final String TYPE = "portal.service_request_dispatch";
    private static final Logger LOG = LoggerFactory.getLogger(ServiceRequestDispatchJob.class);

    record Payload(UUID requestId) {
    }

    private record Sent(UUID messageId, UUID conversationId, String status) {
    }

    private final ServiceRequestRepository requests;
    private final MessageUseCases messages;
    private final TenantExecution tenantExecution;
    private final ApplicationEventPublisher events;
    private final AuditTrail auditTrail;
    private final PortalProperties properties;
    private final PortalMetrics metrics;
    private final Clock clock;

    ServiceRequestDispatchJob(ServiceRequestRepository requests, MessageUseCases messages,
                              TenantExecution tenantExecution, ApplicationEventPublisher events, AuditTrail auditTrail,
                              PortalProperties properties, PortalMetrics metrics, Clock clock) {
        this.requests = requests;
        this.messages = messages;
        this.tenantExecution = tenantExecution;
        this.events = events;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        ServiceRequest request = requests.findById(payload.requestId()).orElse(null);
        if (request == null || request.getStatus() != ServiceRequestStatus.OPEN) {
            return;
        }
        Instant now = Instant.now(clock);
        Sent sent;
        try {
            sent = tenantExecution.callInTenant(context.tenantId(), TYPE + ".send", () -> {
                MessageView m = messages.send(new SendMessageCommand(null, request.getDebtorId(), request.getCreditorId(),
                        null, request.getPreferredChannel().name(), null,
                        PortalAccessService.template(properties.templates().serviceRequest(), request.getPreferredChannel()),
                        Map.of("protocolo", request.getProtocol()), null, null, "portal-request:" + request.getId()));
                return new Sent(m.id(), m.conversationId(), m.status());
            });
        } catch (RuntimeException e) {
            String reason = PortalAccessService.reason(e);
            request.undelivered(reason, now);
            requests.save(request);
            audit(request, reason);
            metrics.serviceRequest(request.getReason().name(), "UNDELIVERED");
            LOG.atWarn().addKeyValue("requestId", request.getId()).addKeyValue("reason", reason)
                    .log("Pedido do portal sem conversa aberta; fica na lista de pedidos do portal");
            return;
        }
        events.publishEvent(new HandoffRequested(Ids.newId(), context.tenantId(), now, null, request.getDebtorId(),
                request.getCreditorId(), sent.conversationId(), request.getReason().handoffReason(), null,
                summary(request), "portal_service_request", request.getId(), Actor.ActorType.SYSTEM.name(), Actor.SYSTEM_ID));
        request.forwarded(sent.conversationId(), sent.messageId(), now);
        requests.save(request);
        audit(request, null);
        metrics.serviceRequest(request.getReason().name(), "FORWARDED");
    }

    /** Resumo para o atendente, sem dados pessoais (a mensagem do devedor fica no pedido, criptografada). */
    static String summary(ServiceRequest r) {
        String what = switch (r.getReason()) {
            case TALK_TO_AGENT -> "pediu para falar com um atendente";
            case DISPUTE -> "quer contestar a dívida (registrar a contestação no caso)";
            case CANNOT_AFFORD -> "não encontrou condição que caiba no orçamento";
            case OTHER -> "fez um pedido";
        };
        return "Portal de autonegociação — protocolo " + r.getProtocol() + ": o devedor " + what
                + ". Responder pelo canal " + r.getPreferredChannel().name()
                + (r.getNote() != null ? ". Há mensagem do devedor no pedido (Portal → Pedidos de atendimento)." : ".");
    }

    private void audit(ServiceRequest r, String failure) {
        auditTrail.record(AuditEntry.of("portal.service_request_dispatched", "portal_service_request", r.getId())
                .creditor(r.getCreditorId()).change("status", ServiceRequestStatus.OPEN, r.getStatus())
                .meta("conversationId", r.getConversationId()).meta("failure", failure));
    }
}
