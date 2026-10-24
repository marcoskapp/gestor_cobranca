package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fatos de negócio e métricas do Pix na conversa (na transação da mudança). Eventos só com ids, enums,
 * códigos e valores do motor — nunca o código Pix, texto do devedor ou dados pessoais. Catálogo em
 * {@code docs/tecnico/logs-e-metricas.md} §3.9 e §5.
 */
@Component
class AssistantPixRecorder {

    private final BusinessEventLogger businessEvents;
    private final AssistantMetrics metrics;
    private final AssistantCodec codec;

    AssistantPixRecorder(BusinessEventLogger businessEvents, AssistantMetrics metrics, AssistantCodec codec) {
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.codec = codec;
    }

    /** Pedido avaliado no turno: {@code assistant.pix_issued} | {@code pix_denied} | {@code pix_suggested}. */
    void evaluated(PixRequest r, String channel) {
        BusinessEvent event;
        switch (r.getDecision()) {
            case ISSUED -> {
                if (!r.getDeliveryStatus().open()) {
                    delivery(r, channel, null);
                    return;
                }
                metrics.pixIssued(r.getMode().name(), r.isReusedInstruction());
                event = base(BusinessEventTypes.ASSISTANT_PIX_ISSUED, r, channel)
                        .attr("reusedInstruction", r.isReusedInstruction())
                        .attr("expiresInMinutes", r.getExpiresAt() == null ? null
                                : Duration.between(r.getRequestedAt(), r.getExpiresAt()).toMinutes());
            }
            case DENIED -> {
                metrics.pixDenied(r.getDenialCode());
                event = base(BusinessEventTypes.ASSISTANT_PIX_DENIED, r, channel).attr("reason", r.getDenialCode())
                        .attr("failedCriteria", String.join(",", failed(r)));
            }
            case SUGGESTED -> {
                metrics.pixSuggested(r.getMode().name());
                event = base(BusinessEventTypes.ASSISTANT_PIX_SUGGESTED, r, channel);
            }
            default -> throw new IllegalStateException("Decisão inesperada: " + r.getDecision());
        }
        businessEvents.log(event);
    }

    /** Entrega concluída, recusada ou cancelada com o turno: {@code assistant.pix_delivery}. */
    void delivery(PixRequest r, String channel, Instant at) {
        Duration latency = at == null ? null : Duration.between(r.getRequestedAt(), at);
        metrics.pixDelivery(r.getDeliveryStatus().name(), r.getDeliveryCode(), latency);
        BusinessEvent event = base(BusinessEventTypes.ASSISTANT_PIX_DELIVERY, r, channel)
                .attr("outcome", r.getDeliveryStatus()).attr("code", r.getDeliveryCode())
                .attr("instructionId", r.getInstructionId() == null ? null : r.getInstructionId().toString());
        if (latency != null) {
            event.durationMs(Math.max(0, latency.toMillis()));
        }
        businessEvents.log(event);
    }

    /** Pix gerado pelo assistente pago: {@code assistant.pix_paid} (conversão e "pago em até 24 h"). */
    void paid(PixRequest r) {
        boolean within = r.paidWithinWindow();
        metrics.pixPaid(within);
        businessEvents.log(base(BusinessEventTypes.ASSISTANT_PIX_PAID, r, null).attr("paidWithin24h", within)
                .attr("delivered", r.getDeliveredAt() != null)
                .attr("minutesToPay", Duration.between(r.getRequestedAt(), r.getPaidAt()).toMinutes())
                .durationMs(Math.max(0, Duration.between(r.getRequestedAt(), r.getPaidAt()).toMillis())));
    }

    private BusinessEvent base(String type, PixRequest r, String channel) {
        BusinessEvent e = BusinessEvent.of(type).creditor(r.getCreditorId()).portfolio(r.getPortfolioId())
                .debtor(r.getDebtorId()).collectionCase(r.getCaseId()).reference("assistant_pix_request", r.getId())
                .attr("conversationId", r.getConversationId().toString())
                .attr("turnId", r.getTurnId() == null ? null : r.getTurnId().toString())
                .attr("mode", r.getMode())
                .attr("policyId", r.getPolicyId() == null ? null : r.getPolicyId().toString())
                .attr("decision", r.getDecision())
                .attr("agreementId", r.getAgreementId() == null ? null : r.getAgreementId().toString())
                .attr("installmentNumber", r.getInstallmentNumber())
                .attr("installmentKind", r.getInstallmentKind());
        if (channel != null) {
            e.channel(channel);
        }
        if (r.getAmount() != null) {
            e.amount(r.getAmount());
        }
        return e;
    }

    private List<String> failed(PixRequest r) {
        return codec.criteria(r.getCriteria()).stream().filter(c -> !c.passed()).map(AssistantCodec.Criterion::criterion)
                .toList();
    }
}
