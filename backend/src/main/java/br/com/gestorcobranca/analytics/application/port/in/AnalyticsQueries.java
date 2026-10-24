package br.com.gestorcobranca.analytics.application.port.in;

import br.com.gestorcobranca.analytics.domain.model.Ratio;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Consultas dos painéis (definições, fórmulas e denominadores em docs/negocio/indicadores.md).
 * Escopo de credores sempre aplicado; dados por pessoa (agrupamento ou filtro por operador/equipe
 * e o painel de produtividade) seguem o escopo próprio/equipe/tudo das permissões
 * {@code PERFORMANCE_VIEW_*} — fora do escopo, 403 com {@code SCOPE_DENIED}.
 */
public interface AnalyticsQueries {

    /** Painel operacional: carteira, saldo vencido e elegível, casos por estado e fila pendente. */
    PortfolioReport portfolio(ReportFilter filter);

    /** Valor recuperado (pago confirmado, líquido de estornos), taxa de recuperação, tempo e custo. */
    RecoveryReport recovery(ReportFilter filter, String groupBy);

    /** Funil contato → proposta → acordo → 1ª parcela e qualidade da safra de acordos. */
    ConversionReport conversion(ReportFilter filter, String groupBy);

    /** Produtividade por operador ou equipe, a partir dos fatos do incentive. */
    ProductivityReport productivity(ReportFilter filter, String groupBy);

    /** Confiabilidade: integrações, mensagens, conciliação, jobs e importações. */
    ReliabilityReport reliability(ReportFilter filter);

    /** Carimbo "dados até" de cada conjunto materializado da assessoria. */
    List<DatasetFreshness> freshness();

    // ------------------------------------------------------------------ comuns

    /**
     * @param dataUntil           dados consolidados até este instante (menor carimbo dos conjuntos
     *                            materializados que o relatório usa; nulo = ainda não atualizado)
     * @param notApplicableFilters filtros informados que não se aplicam a este relatório
     * @param peopleRestricted    dados por pessoa limitados ao escopo do usuário (próprio/equipe)
     * @param unavailableMetrics  indicadores sem fonte de dados no produto (ex.: custo de canal)
     */
    record ReportMeta(LocalDate from, LocalDate to, String groupBy, Instant dataUntil,
                      List<String> notApplicableFilters, boolean peopleRestricted, List<String> unavailableMetrics) {
    }

    record CountRow(String key, String label, long count) {
    }

    record DatasetFreshness(String dataset, Instant dataUntil, Instant refreshedAt, long durationMs, long rowCount) {
    }

    // ---------------------------------------------------------------- carteira

    /**
     * @param referenceDate data de referência da posição (a do último refresh)
     * @param totals        totais do recorte
     */
    record PortfolioReport(ReportMeta meta, LocalDate referenceDate, PortfolioTotals totals,
                           List<PortfolioRow> byCreditor, List<PortfolioRow> byPortfolio,
                           List<PortfolioRow> byDelayBucket, List<CaseStatusRow> casesByStatus,
                           List<QueueRow> pendingQueue) {
    }

    /**
     * @param portfolioAmount    valor da carteira: saldo em aberto dos títulos em cobrança
     * @param overdueAmount      saldo vencido (vencimento anterior à data de referência)
     * @param overdueShare       saldo vencido ÷ valor da carteira
     * @param eligibleBalance    saldo elegível dos casos não encerrados
     * @param eligibleShare      saldo elegível ÷ saldo em aberto dos casos não encerrados
     * @param pendingQueueShare  casos elegíveis aguardando na fila ÷ casos elegíveis
     */
    record PortfolioTotals(BigDecimal portfolioAmount, long openTitles, BigDecimal overdueAmount, long overdueTitles,
                           Ratio overdueShare, BigDecimal caseOpenBalance, BigDecimal eligibleBalance,
                           Ratio eligibleShare, long openCases, long eligibleCases, long pendingQueueCases,
                           BigDecimal pendingQueueBalance, Ratio pendingQueueShare) {
    }

    record PortfolioRow(String key, String label, BigDecimal portfolioAmount, long openTitles, BigDecimal overdueAmount,
                        Ratio overdueShare, BigDecimal eligibleBalance, long openCases, long eligibleCases) {
    }

    record CaseStatusRow(String status, long cases, BigDecimal openBalance, BigDecimal eligibleBalance,
                         long eligibleCases) {
    }

    record QueueRow(String queueId, String label, long cases, BigDecimal eligibleBalance) {
    }

    // -------------------------------------------------------------- recuperação

    /**
     * @param recoveryRate valor recuperado líquido ÷ saldo elegível atribuível no início do período
     * @param reversalShare estornado ÷ recebido bruto
     */
    record RecoveryTotals(BigDecimal recoveredGross, BigDecimal reversed, BigDecimal recoveredNet, long receipts,
                          Ratio recoveryRate, Ratio reversalShare) {
    }

    record RecoveryRow(String key, String label, BigDecimal recoveredGross, BigDecimal reversed,
                       BigDecimal recoveredNet, long receipts, Ratio recoveryRate) {
    }

    /** Dias da formalização do acordo ao 1º recebimento (acordos com o 1º recebimento no período). */
    record TimeToReceipt(long agreements, BigDecimal averageDays, BigDecimal medianDays, BigDecimal p90Days) {
    }

    /**
     * @param commissionPerReal comissão variável a pagar aos negociadores ÷ valor recuperado líquido,
     *                          nos meses inteiros do período (extratos aprovados/exportados)
     * @param monthFrom         primeiro mês inteiro considerado (nulo se nenhum)
     */
    record CostPerRecovered(Ratio commissionPerReal, LocalDate monthFrom, LocalDate monthTo, long statements) {
    }

    record RecoveryReport(ReportMeta meta, RecoveryTotals totals, List<RecoveryRow> rows, TimeToReceipt timeToReceipt,
                          CostPerRecovered cost) {
    }

    // ---------------------------------------------------------------- conversão

    /** Volumes de cada etapa no período (funil de fluxo) e a conversão de cada etapa para a seguinte. */
    record Funnel(long effectiveContacts, long proposals, long agreements, long firstInstallmentsPaid,
                  Ratio contactToProposal, Ratio proposalToAgreement, Ratio agreementToFirstInstallment,
                  Ratio contactToAgreement) {
    }

    /**
     * Linha da safra. {@code effectiveContacts}/{@code proposals} nulos quando a dimensão não se
     * aplica (origem do acordo). {@code agreedAmount} é o valor acordado; {@code paidAmount} o que
     * foi efetivamente pago (recebimentos líquidos de estornos) — acordo não pago não é recuperação.
     */
    record ConversionRow(String key, String label, Long effectiveContacts, Long proposals, long agreements,
                         BigDecimal agreedAmount, BigDecimal paidAmount, Ratio paidShare, long firstInstallmentsPaid,
                         Ratio firstInstallmentRate, Ratio contactToAgreement) {
    }

    /**
     * Quebra precoce por safra: acordos formalizados no período com idade ≥ janela (maduros) e,
     * entre eles, os quebrados até a janela.
     */
    record BreakRate(int windowDays, long matured, long broken, Ratio rate) {
    }

    record CohortQuality(long agreements, BigDecimal agreedAmount, BigDecimal paidAmount, Ratio paidShare,
                         long installmentsPaid, BigDecimal installmentsPaidAmount, long renegotiations,
                         Ratio renegotiationRate, List<BreakRate> breaks) {
    }

    record ConversionReport(ReportMeta meta, Funnel funnel, CohortQuality quality, List<ConversionRow> rows) {
    }

    // ------------------------------------------------------------ produtividade

    /**
     * @param workedCases           casos com ≥ 1 interação da pessoa no período (por equipe/total:
     *                              soma das pessoas)
     * @param contactRate           contatos efetivos ÷ tentativas
     * @param averageHandlingSeconds tempo de conversação ÷ chamadas atendidas
     * @param agreementsPerLoggedHour acordos formalizados ÷ horas logadas
     */
    record ProductivityRow(String key, String label, long workedCases, long attempts, long effectiveContacts,
                           Ratio contactRate, long answeredCalls, long talkSeconds, Ratio averageHandlingSeconds,
                           BigDecimal loggedHours, long agreements, Ratio agreementsPerLoggedHour,
                           BigDecimal recoveredNet) {
    }

    record ProductivityReport(ReportMeta meta, ProductivityRow total, List<ProductivityRow> rows) {
    }

    // ----------------------------------------------------------- confiabilidade

    record IntegrationHealth(long paymentInstructions, long paymentInstructionsFailed, Ratio instructionFailureRate,
                             List<CountRow> instructionFailuresByCode, long calls, long callsFailed,
                             Ratio callFailureRate, long statementImports, long statementImportsFailed) {
    }

    record MessageHealth(long outbound, long failed, Ratio failureRate, List<CountRow> failuresByCode,
                         long statementLines, long duplicateStatementLines, Ratio duplicateLineRate,
                         long duplicatePaymentDivergences, long slaBreaches) {
    }

    record ReconciliationHealth(long opened, List<CountRow> openedByType, long openNow, BigDecimal openNowAmount,
                                long resolved, BigDecimal medianResolutionHours) {
    }

    record JobHealth(long deadNow, List<CountRow> deadByType, long finishedInPeriod, long deadInPeriod,
                     Ratio deadRate) {
    }

    record ImportHealth(long batches, long failed, Ratio failureRate, BigDecimal medianSeconds, BigDecimal p95Seconds,
                        long rowsRead, long rowsWithError, Ratio rowErrorRate) {
    }

    record ReliabilityReport(ReportMeta meta, IntegrationHealth integrations, MessageHealth messages,
                             ReconciliationHealth reconciliation, JobHealth jobs, ImportHealth imports) {
    }
}
