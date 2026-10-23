package br.com.gestorcobranca.incentive.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Extratos de comissão, razão, contestação, ajuste e aprovação. Leitura no escopo
 * próprio/equipe/tudo (consulta a remuneração de terceiro gera log de acesso); comandos de
 * gestão exigem {@code COMMISSION_STATEMENT_MANAGE} e a aprovação {@code COMMISSION_STATEMENT_APPROVE}.
 */
public interface StatementUseCases {

    /** @param userId null = todos os visíveis ao ator */
    PageResponse<StatementSummary> list(YearMonth period, UUID userId, String status, PageQuery page);

    StatementDetail get(UUID statementId);

    /** Lançamentos do razão de um negociador (inclusive em carência), opcionalmente de um período de competência. */
    PageResponse<LedgerEntryView> ledger(UUID userId, YearMonth period, PageQuery page);

    /** Prévia em tempo real do período corrente (lançamentos liberados e em carência), sem gravar. */
    StatementPreview preview(UUID userId);

    /** Recalcula um extrato ainda não aprovado (após nova monitoria ou ocorrência dispensada). */
    StatementDetail recalculate(UUID statementId);

    /** Contestação item a item pelo próprio negociador. */
    DisputeView openDispute(UUID statementId, DisputeCommand command);

    /** @param adjustmentAmount ajuste a aplicar junto da aceitação (null = sem ajuste) */
    DisputeView resolveDispute(UUID statementId, UUID disputeId, boolean accepted, String resolution,
                               BigDecimal adjustmentAmount);

    AdjustmentView adjust(UUID statementId, BigDecimal amount, String reason);

    /** Segunda aprovação de ajuste acima do limite (outra pessoa). */
    AdjustmentView decideAdjustment(UUID statementId, UUID adjustmentId, boolean approve, String note);

    /** Aprovação financeira, segregada de quem fechou o período e de quem ajustou. */
    StatementDetail approve(UUID statementId);

    record DisputeCommand(Integer lineNumber, UUID ledgerEntryId, UUID agreementId, String reason) {
    }

    record StatementSummary(UUID id, YearMonth period, UUID userId, String userName, String status, String outcome,
                            String planCode, Integer planVersion, BigDecimal calculatedAmount,
                            BigDecimal adjustmentsAmount, BigDecimal payableAmount, BigDecimal withheldAmount,
                            BigDecimal creditedRecovery, Instant approvedAt) {
    }

    record StatementLineView(int number, String component, String description, String segmentKey, BigDecimal base,
                             BigDecimal rate, BigDecimal factor, BigDecimal amount) {
    }

    record StatementDetail(StatementSummary summary, List<StatementLineView> lines, String calculationMemory,
                           List<DisputeView> disputes, List<AdjustmentView> adjustments, UUID approvedBy) {
    }

    record DisputeView(UUID id, Integer lineNumber, UUID ledgerEntryId, UUID agreementId, String reason, String status,
                       UUID openedBy, Instant openedAt, String resolution, UUID resolvedBy, Instant resolvedAt,
                       UUID adjustmentId) {
    }

    record AdjustmentView(UUID id, UUID disputeId, BigDecimal amount, String reason, String status, UUID requestedBy,
                          Instant requestedAt, UUID decidedBy, Instant decidedAt, String decisionNote) {
    }

    record LedgerEntryView(UUID id, String kind, String status, String role, UUID agreementId, UUID paymentId,
                           UUID creditorId, UUID portfolioId, String delayBucket, String origin, String channel,
                           BigDecimal baseAmount, BigDecimal creditWeight, BigDecimal renegotiationFactor,
                           BigDecimal creditedAmount, String creditNote, LocalDate paidOn, LocalDate clearsOn,
                           YearMonth period, UUID statementId) {
    }

    /**
     * @param clearedAmount    variável calculado só com o que já foi liberado
     * @param projectedAmount  variável se todos os lançamentos em carência forem liberados
     */
    record StatementPreview(UUID userId, YearMonth period, String planCode, Integer planVersion, String outcome,
                            BigDecimal clearedRecovery, BigDecimal pendingRecovery, BigDecimal clearedAmount,
                            BigDecimal projectedAmount, List<StatementLineView> projectedLines) {
    }
}
