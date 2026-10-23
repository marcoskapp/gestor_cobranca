package br.com.gestorcobranca.incentive.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consulta da atribuição de crédito de um acordo (escopo por credor; insumo de contestação). */
public interface AttributionQueries {

    AttributionView byAgreement(UUID agreementId);

    record ParticipantView(String actorType, UUID actorId, String role) {
    }

    record CreditView(String beneficiaryType, UUID beneficiaryId, String role, BigDecimal weight,
                      BigDecimal renegotiationFactor, String reason) {
    }

    record AttributionView(UUID id, UUID agreementId, UUID caseId, UUID creditorId, UUID portfolioId, UUID teamId,
                           String origin, String channel, Integer concessionLevel, BigDecimal envelopeUsage,
                           String delayBucket, BigDecimal agreementAmount, BigDecimal incrementalAmount,
                           UUID replacedAgreementId, Instant formalizedAt, UUID planId,
                           List<ParticipantView> participants, List<CreditView> credits) {
    }
}
