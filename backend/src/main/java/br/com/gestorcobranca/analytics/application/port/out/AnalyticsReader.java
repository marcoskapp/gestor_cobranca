package br.com.gestorcobranca.analytics.application.port.out;

import br.com.gestorcobranca.analytics.domain.model.Dimension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Leituras agregadas sobre as views e snapshots do schema {@code analytics} (sob RLS da
 * assessoria). Devolve medidas aditivas; as fórmulas ficam no serviço/domínio.
 */
public interface AnalyticsReader {

    // carteira (snapshot)
    LocalDate positionReferenceDate();

    List<PositionRow> titlePosition(Slice slice);

    List<CasePositionRow> casePosition(Slice slice);

    // recuperação
    List<RecoveryRaw> recovery(Slice slice, Dimension groupBy);

    /** Saldo elegível atribuível no início do período, por chave da dimensão (assignee para pessoa). */
    Map<String, BigDecimal> eligibleAtStart(Slice slice, Dimension groupBy);

    TimeToReceiptRaw timeToReceipt(Slice slice);

    /** Valor a pagar dos extratos aprovados/exportados com competência entre os meses (inclusive). */
    StatementCost statementCost(LocalDate monthFrom, LocalDate monthTo);

    // conversão
    Map<String, Long> effectiveContacts(Slice slice, Dimension groupBy);

    Map<String, Long> proposals(Slice slice, Dimension groupBy);

    List<CohortRaw> cohort(Slice slice, Dimension groupBy, Instant asOf);

    InstallmentsRaw installmentsPaid(Slice slice);

    // produtividade (por pessoa)
    List<EffortRaw> effort(Slice slice);

    Map<UUID, Long> loggedSeconds(Slice slice);

    Map<UUID, Long> agreementsByOperator(Slice slice);

    Map<UUID, BigDecimal> recoveredByOperator(Slice slice);

    // confiabilidade
    ReliabilityRaw reliability(Slice slice);

    /**
     * Contestações agrupadas ({@code groupBy}: ALL, TYPE, ORIGIN ou CREDITOR): fluxo no período (abertas, resolvidas,
     * procedentes, no prazo, dias até a resolução) e posição atual (abertas, vencidas em {@code today}).
     */
    List<DisputeRaw> disputes(Slice slice, String groupBy, LocalDate today);

    /** Pedidos ao credor agrupados como em {@link #disputes}: enviados e respondidos no período, horas até a resposta. */
    List<CreditorRequestRaw> creditorRequests(Slice slice, String groupBy);

    /** Contestações abertas por responsável (chave nula = sem responsável). */
    List<DisputeBacklogRaw> disputeBacklog(Slice slice, LocalDate today);

    // apoio
    List<TeamRaw> teams();

    Map<String, String> labels(Dimension dimension, Collection<String> keys);

    Map<String, String> queueLabels(Collection<String> queueIds);

    List<FreshnessRaw> freshness();

    // --------------------------------------------------------------- registros

    record PositionRow(UUID creditorId, UUID portfolioId, String delayBucket, long openTitles,
                       BigDecimal portfolioAmount, long overdueTitles, BigDecimal overdueAmount) {
    }

    record CasePositionRow(UUID creditorId, UUID portfolioId, String delayBucket, String status, boolean eligible,
                           String ownerType, UUID queueId, long cases, BigDecimal openBalance,
                           BigDecimal eligibleBalance) {
    }

    record RecoveryRaw(String key, BigDecimal gross, BigDecimal reversed, long receipts) {
    }

    record TimeToReceiptRaw(long agreements, BigDecimal averageDays, BigDecimal medianDays, BigDecimal p90Days) {
    }

    record StatementCost(long statements, BigDecimal payable) {
    }

    /**
     * Medidas da safra por chave. {@code brokenWithin*}/{@code matured*}: janelas de 30, 60 e 90 dias.
     */
    record CohortRaw(String key, long agreements, BigDecimal agreedAmount, BigDecimal paidAmount,
                     long firstInstallmentsPaid, long renegotiations, long matured30, long broken30, long matured60,
                     long broken60, long matured90, long broken90) {
    }

    record InstallmentsRaw(long count, BigDecimal amount) {
    }

    record EffortRaw(UUID userId, long workedCases, long attempts, long effectiveContacts, long answeredCalls,
                     long talkSeconds) {
    }

    record ReliabilityRaw(long instructions, long instructionsFailed, Map<String, Long> instructionFailures,
                          long calls, long callsFailed, long statementImports, long statementImportsFailed,
                          long outbound, long messagesFailed, Map<String, Long> messageFailures,
                          long statementLines, long duplicateLines, long duplicatePaymentDivergences,
                          long slaBreaches, long divergencesOpened, Map<String, Long> divergencesByType,
                          long divergencesOpenNow, BigDecimal divergencesOpenAmount, long divergencesResolved,
                          BigDecimal medianResolutionHours, long deadNow, Map<String, Long> deadByType,
                          long jobsFinished, long jobsDead, long batches, long batchesFailed,
                          BigDecimal batchMedianSeconds, BigDecimal batchP95Seconds, long rowsRead,
                          long rowsWithError) {
    }

    /** @param resolutionDays soma dos dias corridos (fração) entre abertura e resolução das resolvidas no período */
    record DisputeRaw(String key, long opened, long resolved, long withdrawn, long upheld, long resolvedWithinDeadline,
                      BigDecimal resolutionDays, long openNow, long overdueNow) {
    }

    /** @param responseHours soma das horas entre o envio e a resposta dos pedidos respondidos no período */
    record CreditorRequestRaw(String key, long sent, long answered, BigDecimal responseHours, long awaitingNow,
                              long overdueNow) {
    }

    record DisputeBacklogRaw(UUID assigneeId, long open, long overdue, long internalOverdue, long awaitingCreditor) {
    }

    record TeamRaw(UUID teamId, String name, UUID supervisorId, boolean active, Set<UUID> members) {
    }

    record FreshnessRaw(String dataset, Instant dataUntil, Instant refreshedAt, long durationMs, long rowCount) {
    }
}
