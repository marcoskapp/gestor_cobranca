package br.com.gestorcobranca.incentive.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Leitura agregada dos fatos para os indicadores do §2 (consultas SQL sobre as tabelas de fatos).
 * As fórmulas são aplicadas pelo caso de uso sobre estas somas.
 */
public interface IndicatorReader {

    enum Dimension { USER, CREDITOR, PORTFOLIO, DELAY_BUCKET, CHANNEL, NONE }

    /**
     * Filtro dos fatos.
     *
     * @param userIds     null = todos os negociadores
     * @param creditorIds vazio = todos (já restrito ao escopo do ator)
     */
    record Filter(LocalDate from, LocalDate to, Collection<UUID> userIds, Collection<UUID> creditorIds,
                  Collection<UUID> portfolioIds, Collection<String> delayBuckets, Collection<String> channels) {
    }

    /** Somas aditivas por grupo; {@code groupKey} é o valor da dimensão (texto) ou "ALL". */
    record Totals(String groupKey, long attempts, long effectiveContacts, long answeredCalls, long talkSeconds,
                  long agreements, BigDecimal negotiatedAmount, BigDecimal agreementAmount, BigDecimal updatedBalance,
                  BigDecimal discountAmount, BigDecimal downPaymentAmount, BigDecimal envelopeUsageSum, long envelopeCount,
                  long concessionLevelSum, long concessionCount, long firstInstallments, BigDecimal accruedAmount,
                  BigDecimal reversedAmount, Long workedCases, BigDecimal medianResponseSeconds) {
    }

    List<Totals> totals(Filter filter, Dimension groupBy);

    /** Tempo logado por usuário (para dimensões USER/NONE). */
    record LoggedTime(UUID userId, long seconds) {
    }

    List<LoggedTime> loggedTime(LocalDate from, LocalDate to, Collection<UUID> userIds);

    /** Safra de acordos formalizados no intervalo (closer humano), por grupo. */
    record SafraTotals(String groupKey, long formalized, long broken30, long broken60, long broken90, long firstPaid,
                       long renegotiated) {
    }

    List<SafraTotals> safra(Filter filter, Dimension groupBy);

    /** Ocorrências confirmadas e monitorias por usuário no intervalo. */
    record QualityTotals(UUID userId, long complaints, long upheldDisputes, long complianceViolations,
                         long severeViolations, long evaluations, BigDecimal averageScore) {
    }

    List<QualityTotals> quality(LocalDate from, LocalDate to, Collection<UUID> userIds);

    BigDecimal eligibleBalanceAt(Collection<UUID> userIds, Instant at, Collection<UUID> creditorIds);
}
