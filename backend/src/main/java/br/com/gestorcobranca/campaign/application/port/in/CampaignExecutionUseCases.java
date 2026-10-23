package br.com.gestorcobranca.campaign.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Execução das campanhas: ativação, pausa, encerramento e reprocessamento seguro; inscrições e painel
 * de execução. O motor roda por jobs ({@code campaign.enroll}, {@code campaign.step}); cada etapa
 * tem chave idempotente (inscrição × etapa × iteração), também enviada ao communication.
 * <p>
 * Escopo: o credor da campanha (404 leitura, 403 comando). Auditado; ativação e pausa registram
 * {@code campaign.activated}/{@code campaign.paused}.
 */
public interface CampaignExecutionUseCases {

    /** Ativa (ou reativa) a campanha: exige régua publicada e segmento ativo. */
    CampaignExecutionResult activate(UUID campaignId);

    /** Pausa a campanha: nenhuma etapa roda até a reativação (inscrições preservadas). */
    CampaignExecutionResult pause(UUID campaignId, String note);

    /** Encerra a campanha e as inscrições em andamento. */
    CampaignExecutionResult finish(UUID campaignId, String note);

    /** Reprocessa as inscrições com falha (mesma etapa e chave idempotente: sem duplicidade). */
    CampaignExecutionResult reprocess(UUID campaignId);

    PageResponse<EnrollmentView> enrollments(UUID campaignId, EnrollmentFilter filter, PageQuery page);

    EnrollmentDetail enrollment(UUID enrollmentId);

    /** Inscrições do caso nos credores do escopo (ficha do caso, jurídico). */
    List<EnrollmentView> byCase(UUID caseId);

    EnrollmentView pauseEnrollment(UUID enrollmentId, String note);

    EnrollmentView resumeEnrollment(UUID enrollmentId);

    EnrollmentView stopEnrollment(UUID enrollmentId, String note);

    CampaignPanel panel(UUID campaignId);

    /** @param affected inscrições afetadas (reprocessadas, encerradas…) */
    record CampaignExecutionResult(UUID campaignId, String status, int affected) {
    }

    /** @param status ACTIVE, PAUSED, FAILED, HOLDOUT, ENDED */
    record EnrollmentFilter(String status, UUID debtorId, UUID caseId) {
    }

    record EnrollmentView(UUID id, UUID campaignId, UUID cadenceId, UUID caseId, UUID debtorId, UUID creditorId,
                          UUID portfolioId, String status, UUID experimentId, String variantCode, boolean control,
                          Instant enrolledAt, String nextStepCode, int nextStepIteration, Instant nextRunAt,
                          String lastStepCode, Instant lastStepAt, int stepsExecuted, int stepsSkipped,
                          String pauseReason, Instant pausedAt, Instant resumeAt, String endReason, Instant endedAt,
                          Instant observeUntil, Instant repliedAt, Instant effectiveContactAt, String lastErrorCode,
                          Instant lastErrorAt, long version) {
    }

    record StepExecutionView(UUID id, String stepCode, int iteration, String actionType, String channel, String outcome,
                             String reason, String reasonDetail, UUID messageId, String messageStatus, UUID taskId,
                             String variantCode, Instant executedAt) {
    }

    record EnrollmentDetail(EnrollmentView enrollment, List<StepExecutionView> steps) {
    }

    /**
     * Painel de execução. Denominadores explícitos: inscrições (por situação), execuções de etapa,
     * mensagens enviadas pela régua; resultados atribuídos na janela de atribuição.
     *
     * @param suppressedByConcurrency casos do segmento não inscritos por outra campanha do grupo
     */
    record CampaignPanel(UUID campaignId, Instant computedAt, long enrollments, Map<String, Long> enrollmentsByStatus,
                         Map<String, Long> pausedByReason, Map<String, Long> endedByReason,
                         long suppressedByConcurrency, StepTotals steps, List<StepBreakdown> byStep,
                         Map<String, Long> skippedByReason, Map<String, Long> failedByReason, MessageTotals messages,
                         OutcomeTotals outcomes) {
    }

    record StepTotals(long executed, long skipped, long failed) {
    }

    record StepBreakdown(String stepCode, String actionType, String channel, long executed, long skipped, long failed) {
    }

    /** @param blocked mensagens negadas pelo porteiro (verificação prévia ou no envio) */
    record MessageTotals(long sent, long delivered, long read, long failed, long blocked) {
    }

    /**
     * @param repliedEnrollments    inscrições com resposta
     * @param effectiveContacts     inscrições com contato efetivo
     * @param agreements            acordos formalizados atribuídos
     * @param payingEnrollments     inscrições com pagamento confirmado (líquido de estornos &gt; 0)
     * @param recoveredAmount       pagamentos confirmados atribuídos, líquidos de estornos
     */
    record OutcomeTotals(long repliedEnrollments, long effectiveContacts, long agreements, BigDecimal agreementAmount,
                         long payingEnrollments, BigDecimal recoveredAmount) {
    }
}
