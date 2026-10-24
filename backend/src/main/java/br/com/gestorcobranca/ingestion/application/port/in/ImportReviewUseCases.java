package br.com.gestorcobranca.ingestion.application.port.in;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.FileContent;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conferência da importação (assistente em passos, {@code docs/tecnico/desenho/importacao.md}): formato numérico por
 * coluna, validação das linhas, totais de controle conferidos com a planilha, alertas de valor (ticket médio) e
 * efetivação com confirmação — e aprovação de segunda pessoa acima do limite. O estado fica no servidor: o usuário
 * pode sair e voltar a qualquer passo. Todas as operações respeitam o escopo por credor; leituras exigem
 * {@code IMPORT_VIEW}, ações {@code IMPORT_EXECUTE} e a aprovação {@code IMPORT_APPROVE}.
 */
public interface ImportReviewUseCases {

    /** Passo 1: envia o arquivo e cria a conferência com a primeira prévia. */
    ReviewView start(StartReviewCommand command);

    /** Inicia a conferência com o arquivo de um lote existente (prévia antiga, importação a reprocessar). */
    ReviewView startFromBatch(UUID batchId);

    ReviewView get(UUID reviewId);

    PageResponse<ReviewSummaryView> search(ReviewSearch filter, PageQuery page);

    /** Salva o passo em que o usuário está (1 a 6). */
    ReviewView moveTo(UUID reviewId, int step);

    /**
     * Passo 2: confirma (ou decide) a interpretação das colunas monetárias. Se a prévia aguardava a decisão, a validação
     * começa nela; se a decisão muda o que já foi validado, uma nova prévia do mesmo arquivo é criada.
     */
    ReviewView confirmFormat(UUID reviewId, FormatCommand command);

    /** Passo 4: confere os totais informados pelo usuário com os do arquivo convertido. */
    TotalsCheckView checkTotals(UUID reviewId, TotalsCommand command);

    /** Quebras dos totais (faixa de vencimento, situação, produto, validade) — depois da primeira conferência. */
    List<BreakdownRowView> breakdown(UUID reviewId);

    /** Passo 5: alertas de valor, do mais prioritário ao menos. */
    PageResponse<AlertView> alerts(UUID reviewId, String type, String status, PageQuery page);

    /** Decisão sobre alertas, por seleção ou em lote (todos os abertos, opcionalmente de um tipo). */
    AnalysisView decideAlerts(UUID reviewId, AlertDecisionCommand command);

    /** Passo 6: efetiva (cria o lote de importação) ou, acima do limite, pede aprovação. */
    ReviewView commit(UUID reviewId, CommitCommand command);

    /** Aprovação de segunda pessoa ({@code IMPORT_APPROVE}): cria o lote de importação. */
    ReviewView approve(UUID reviewId, ApprovalCommand command);

    ReviewView reject(UUID reviewId, String reason);

    ReviewView cancel(UUID reviewId, String reason);

    record StartReviewCommand(UUID portfolioId, UUID layoutId, String fileName, String contentType, long size,
                              FileContent content) {
    }

    /** @param decisions interpretação por coluna ({@code valor_principal → COMMA}); colunas omitidas mantêm a aplicada */
    record FormatCommand(UUID batchId, Map<String, String> decisions) {
    }

    /** Totais da planilha do usuário (ao menos um). */
    record TotalsCommand(Long rows, BigDecimal principalTotal, BigDecimal balanceTotal, BigDecimal contractAmountTotal) {
    }

    /**
     * @param alertIds    alertas escolhidos; vazio com {@code allOpen} = todos os abertos (do tipo, se informado)
     * @param decision    CONFIRM, EXCLUDE ou REOPEN
     * @param justification obrigatória para CONFIRM e EXCLUDE
     */
    record AlertDecisionCommand(List<UUID> alertIds, boolean allOpen, String type, String decision,
                                String justification) {
    }

    /** @param forceReason obrigatória quando o arquivo já foi importado com sucesso na carteira */
    record CommitCommand(String confirmationText, String forceReason) {
    }

    record ApprovalCommand(String confirmationText, String note) {
    }

    /** @param status OPEN, AWAITING_APPROVAL, COMMITTED, REJECTED ou CANCELLED (vários separados por vírgula) */
    record ReviewSearch(UUID creditorId, UUID portfolioId, String status) {
    }

    record ReviewSummaryView(UUID id, UUID creditorId, UUID portfolioId, String status, int currentStep,
                             String fileName, UUID currentBatchId, String batchStatus, UUID importBatchId,
                             boolean approvalRequired, BigDecimal principalToImport, UUID createdBy, Instant createdAt,
                             UUID commitRequestedBy, Instant commitRequestedAt) {
    }

    /**
     * Estado completo da conferência.
     *
     * @param batch            prévia corrente
     * @param impact           impacto da efetivação (prévia validada), ou nulo
     * @param blockers         motivos que impedem a efetivação agora
     * @param confirmationText palavra que o usuário digita para efetivar
     * @param canApprove       o usuário atual pode aprovar (permissão e segregação)
     */
    record ReviewView(UUID id, UUID creditorId, UUID portfolioId, String status, int currentStep, int previews,
                      ImportBatchView batch, FormatView format, TotalsStateView totals, AnalysisView analysis,
                      ImpactView impact, List<BlockerView> blockers, String confirmationText, CommitView commit,
                      boolean canApprove, UUID createdBy, Instant createdAt) {
    }

    /**
     * @param awaitingDecision a prévia está parada esperando a decisão (colunas ambíguas ou misturadas)
     * @param confirmed        o formato da prévia corrente foi confirmado pelo usuário
     * @param textValues       valores monetários em texto (no XLSX: "números armazenados como texto")
     */
    record FormatView(boolean available, boolean awaitingDecision, boolean confirmed, Instant confirmedAt,
                      UUID confirmedBy, long rows, long typedValues, long textValues, List<ColumnFormatView> columns) {
    }

    /**
     * @param decision   interpretação aplicada (nula se ainda não decidida)
     * @param suggestion convenção das demais colunas do arquivo (apenas exibida)
     */
    record ColumnFormatView(String column, String status, boolean requiresDecision, long typedCount, long textCount,
                            Map<String, Long> styleCounts, String decision, String decisionSource, String recommended,
                            String suggestion, List<FormatOptionView> options) {
    }

    /** Efeito de uma interpretação: soma, inválidos e os exemplos reais convertidos. */
    record FormatOptionView(String option, BigDecimal total, long invalidCount, List<ExampleView> examples) {
    }

    /** @param value valor convertido (nulo se a interpretação não lê o valor; ver {@code problem}) */
    record ExampleView(long rowNumber, String raw, String style, BigDecimal value, String problem) {
    }

    record TotalsStateView(boolean checked, boolean matched, int checks, int mismatches, TotalsCheckView lastCheck) {
    }

    record TotalsCheckView(UUID id, UUID batchId, boolean matched, List<MeasureView> measures, List<HintView> hints,
                           List<SuspectRowView> suspectRows, List<SuspectRowView> equalToDifference,
                           UUID createdBy, Instant createdAt) {
    }

    /**
     * @param scaleExponent n quando informado ÷ calculado ≈ 10ⁿ
     * @param invalidCount  valores da coluna que não puderam ser lidos (fora da soma)
     */
    record MeasureView(String measure, String column, BigDecimal entered, BigDecimal computed, BigDecimal difference,
                       boolean matches, Integer scaleExponent, long invalidCount) {
    }

    /** @param code SCALE, ALTERNATIVE_MATCH, EQUAL_ROW, INVALID_VALUES, MATCHED */
    record HintView(String code, String measure, String message) {
    }

    record SuspectRowView(long rowNumber, String externalId, BigDecimal amount, boolean ambiguous) {
    }

    record BreakdownRowView(String dimension, String key, long rows, BigDecimal principal, BigDecimal balance) {
    }

    /**
     * @param open      alertas abertos (sem decisão)
     * @param openScale abertos de escala (provável separador decimal)
     * @param references referências estatísticas usadas (tipo → tamanho da amostra) e medianas, para exibição
     */
    record AnalysisView(String status, int total, boolean truncated, long open, long openScale, long confirmed,
                        long excluded, long rowsExcluded, List<AlertTypeView> byType, Map<String, Object> references) {
    }

    record AlertTypeView(String type, boolean scale, long open, long confirmed, long excluded) {
    }

    record AlertView(UUID id, long rowNumber, String externalId, String type, boolean scale, int priority, String field,
                     BigDecimal amount, BigDecimal expectedLow, BigDecimal expectedHigh, String reference,
                     Long referenceSize, Integer suggestedExponent, BigDecimal suggestedAmount, String detail,
                     String status, UUID decidedBy, Instant decidedAt, String justification) {
    }

    record ImpactView(long rows, long rowsToImport, long rowsWithError, long rowsExcluded, long newTitles,
                      long existingTitles, long holdBySituation, long closedBySituation, BigDecimal principalToImport,
                      BigDecimal balanceToImport, boolean approvalRequired, BigDecimal approvalThreshold,
                      boolean duplicateFile) {
    }

    record BlockerView(String code, String message) {
    }

    record CommitView(UUID requestedBy, Instant requestedAt, boolean approvalRequired, BigDecimal approvalThreshold,
                      String forceReason, UUID decidedBy, Instant decidedAt, String decisionNote, UUID importBatchId,
                      Instant cancelledAt, UUID cancelledBy, String cancelReason) {
    }
}
