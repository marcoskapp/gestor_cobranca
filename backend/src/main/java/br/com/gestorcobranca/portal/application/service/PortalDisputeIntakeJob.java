package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeIntakeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeIntakeUseCases.PortalDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeIntakeUseCases.PortalDisputeResult;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.model.ServiceRequest;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestDispute;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestReason;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestDisputeRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Contestação aberta pelo devedor no portal → contestação no collection (I14 da revisão de produto de 11/2026), como
 * ator de sistema, pela interface pública do collection ({@link DisputeIntakeUseCases}). O prazo do CDC conta do
 * instante do pedido no portal, não da execução do job. Idempotente: o collection não duplica a contestação do mesmo
 * pedido no mesmo caso, e o resultado fica no pedido. Falha transitória → o job é repetido; o pedido continua também
 * na fila humana ({@code portal.service_request_dispatch}).
 */
@Component
class PortalDisputeIntakeJob implements JobHandler<PortalDisputeIntakeJob.Payload> {

    static final String TYPE = "portal.dispute_intake";

    record Payload(UUID requestId) {
    }

    private final ServiceRequestRepository requests;
    private final ServiceRequestDisputeRepository requestDisputes;
    private final PortalConfigurationRepository configurations;
    private final DisputeIntakeUseCases intake;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final PortalMetrics metrics;
    private final Clock clock;

    PortalDisputeIntakeJob(ServiceRequestRepository requests, ServiceRequestDisputeRepository requestDisputes,
                           PortalConfigurationRepository configurations,
                           DisputeIntakeUseCases intake, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                           PortalMetrics metrics, Clock clock) {
        this.requests = requests;
        this.requestDisputes = requestDisputes;
        this.configurations = configurations;
        this.intake = intake;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
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
        ServiceRequestDispute intakeRow = request == null ? null
                : requestDisputes.findByRequestId(request.getId()).orElse(null);
        if (request == null || request.getReason() != ServiceRequestReason.DISPUTE || intakeRow == null
                || !intakeRow.isPending()) {
            return;
        }
        // a carteira escolhida ou, sem escolha, as que o devedor vê no portal do credor
        Set<UUID> portfolios = request.getPortfolioId() != null ? Set.of(request.getPortfolioId())
                : configurations.findById(request.getConfigurationId()).map(PortalConfiguration::getPortfolioIds)
                .orElse(Set.of());
        PortalDisputeResult result = intake.openFromPortal(new PortalDisputeCommand(request.getId(),
                request.getProtocol(), request.getDebtorId(), request.getCreditorId(), portfolios,
                request.getDisputeType(), request.hasNote(), request.getCreatedAt()));
        Instant now = Instant.now(clock);
        intakeRow.registered(result.outcome(), result.disputeIds().size(), now);
        requestDisputes.save(intakeRow);
        auditTrail.record(AuditEntry.of("portal.dispute_registered", "portal_service_request", request.getId())
                .creditor(request.getCreditorId())
                .change("disputeIntakeStatus", "PENDING", result.outcome())
                .meta("disputeIds", result.disputeIds().toString())
                .meta("existingDisputeIds", result.existingDisputeIds().toString()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_DISPUTE_REGISTERED)
                .creditor(request.getCreditorId()).portfolio(request.getPortfolioId()).debtor(request.getDebtorId())
                .reference("portal_service_request", request.getId()).channel("PORTAL")
                .attr("outcome", result.outcome())
                .attr("disputeType", request.getDisputeType())
                .attr("opened", result.disputeIds().size())
                .attr("secondsToRegister", Duration.between(request.getCreatedAt(), now).toSeconds()));
        metrics.disputeIntake(result.outcome());
    }
}
