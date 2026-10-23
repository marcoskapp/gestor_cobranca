package br.com.gestorcobranca.legal.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Medidas extrajudiciais (etapa 4): registro (a via precisa estar autorizada pelo credor),
 * envio/protocolo, efetivação, falha, cancelamento e baixa. Efeitos: o protesto lavrado registra
 * a interrupção da prescrição dos títulos no debt (CC art. 202, III); a execução extrajudicial da
 * garantia atualiza a situação da garantia no debt. O envio automático ({@code dispatch}) só
 * existe com adaptador real; com o adaptador manual responde {@code legal.provider_manual}.
 */
public interface ExtrajudicialMeasureUseCases {

    MeasureView register(UUID matterId, MeasureCommand command);

    MeasureView get(UUID measureId);

    List<MeasureView> list(UUID matterId);

    /** Envio/protocolo lançado manualmente. */
    MeasureView submit(UUID measureId, SubmitCommand command);

    /** Envio pelo provedor da medida (exige {@code DEBTOR_SENSITIVE_VIEW}: a identificação do devedor é transmitida). */
    MeasureView dispatch(UUID measureId);

    /** @param status EFFECTIVE, FAILED, CANCELLED ou REMOVED */
    MeasureView changeStatus(UUID measureId, StatusCommand command);

    /**
     * @param type        NOTICE, PROTEST, CREDIT_BUREAU_LISTING ou COLLATERAL_ENFORCEMENT
     * @param titleIds    títulos do caso jurídico (vazio = todos)
     * @param amount      valor apontado/negativado (nulo = saldo dos títulos)
     * @param priorNoticeOn data da comunicação prévia (negativação)
     */
    record MeasureCommand(String type, Set<UUID> titleIds, UUID collateralId, BigDecimal amount, String authority,
                          LocalDate priorNoticeOn, LocalDate dueDate) {
    }

    record SubmitCommand(LocalDate submittedOn, String protocol) {
    }

    record StatusCommand(String status, LocalDate date, String reason) {
    }

    record MeasureView(UUID id, UUID matterId, String type, Set<UUID> titleIds, UUID collateralId, BigDecimal amount,
                       String authority, String protocol, LocalDate priorNoticeOn, LocalDate submittedOn,
                       LocalDate effectiveOn, LocalDate resolvedOn, LocalDate dueDate, String status,
                       String statusReason, String provider, String providerReference, Instant createdAt) {
    }
}
