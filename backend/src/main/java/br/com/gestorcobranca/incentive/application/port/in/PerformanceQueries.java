package br.com.gestorcobranca.incentive.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Indicadores do negociador (§2), com as fórmulas do desenho aplicadas sobre os fatos
 * materializados. Escopo: próprio ({@code PERFORMANCE_VIEW_OWN}), equipes supervisionadas
 * ({@code PERFORMANCE_VIEW_TEAM}) ou todo o tenant ({@code PERFORMANCE_VIEW_ALL}); fatos sempre
 * restritos ao escopo de credores do ator.
 * <p>
 * Taxas são frações (0,25 = 25%); valores em reais; tempos em segundos. Métricas cuja fonte ainda
 * não publica eventos vêm nulas e listadas em {@link IndicatorReport#unavailableMetrics()}.
 */
public interface PerformanceQueries {

    IndicatorReport indicators(IndicatorQuery query);

    /**
     * @param groupBy NONE, USER, TEAM, CREDITOR, PORTFOLIO, DELAY_BUCKET ou CHANNEL
     */
    record IndicatorQuery(LocalDate from, LocalDate to, UUID userId, UUID teamId, UUID creditorId, UUID portfolioId,
                          String delayBucket, String channel, String groupBy) {
    }

    record IndicatorReport(LocalDate from, LocalDate to, String groupBy, List<IndicatorRow> rows,
                           List<String> unavailableMetrics) {
    }

    record IndicatorRow(String groupKey, Effort effort, Conversion conversion, Financial financial, Quality quality) {
    }

    /**
     * @param loggedSeconds          tempo logado (nulo quando a dimensão não é por pessoa)
     * @param occupancy              tempo em atendimento (chamadas atendidas) ÷ tempo logado
     * @param averageHandlingSeconds TMA: duração média das chamadas atendidas
     * @param medianResponseSeconds  mediana entre mensagem do devedor e resposta do negociador
     * @param callbacksKeptRate      retornos cumpridos: realizados até o horário (com tolerância) ÷ agendados,
     *                               por dia do horário combinado e responsável pelo retorno; agendados
     *                               excluem cancelados e substituídos por reagendamento
     * @param callbacksScheduled     retornos agendados (denominador)
     * @param callbacksKept          retornos cumpridos no prazo (numerador)
     * @param callbacksMissed        retornos atrasados (inclui os cumpridos depois da tolerância)
     */
    record Effort(Long loggedSeconds, BigDecimal occupancy, long workedCases, long attempts, long effectiveContacts,
                  BigDecimal effectiveContactRate, long answeredCalls, BigDecimal averageHandlingSeconds,
                  BigDecimal medianResponseSeconds, BigDecimal callbacksKeptRate, Long handoffsReceived,
                  long callbacksScheduled, long callbacksKept, long callbacksMissed) {
    }

    /**
     * @param promisesKeptRate   promessas cumpridas: KEPT ÷ promessas vencidas, pela data prometida e por quem
     *                           registrou; vencida = prazo encerrado com desfecho (cumprida ou quebrada)
     * @param promisesRegistered promessas registradas no período (dia do registro)
     * @param promisesDue        promessas vencidas (denominador)
     * @param promisesKept       promessas cumpridas (numerador)
     * @param promisesBroken     promessas quebradas
     */
    record Conversion(long agreements, BigDecimal contactToProposal, BigDecimal proposalToAgreement,
                      BigDecimal contactToAgreement, BigDecimal promisesKeptRate, BigDecimal agreementsPerLoggedHour,
                      BigDecimal handoffResolution, long promisesRegistered, long promisesDue, long promisesKept,
                      long promisesBroken) {
    }

    /**
     * @param recoveredAmount pagamentos confirmados creditados (peso de crédito aplicado), líquidos de estornos
     * @param recoveryRate    recuperado ÷ saldo elegível atribuído no início do período
     */
    record Financial(BigDecimal negotiatedAmount, BigDecimal recoveredAmount, BigDecimal recoveryRate,
                     BigDecimal recoveredPerLoggedHour, BigDecimal averageTicket, BigDecimal averageDiscount,
                     BigDecimal envelopeUsageAverage, BigDecimal averageConcessionLevel, BigDecimal averageDownPayment,
                     BigDecimal expectedRecoveryRatio) {
    }

    /** Indicadores de safra (acordos formalizados no período) e de conduta. */
    record Quality(long safraAgreements, BigDecimal earlyBreak30, BigDecimal earlyBreak60, BigDecimal earlyBreak90,
                   BigDecimal firstInstallmentPaidRate, BigDecimal renegotiationRate, BigDecimal reversalRate,
                   Long upheldDisputes, Long complaints, Long complianceViolations, BigDecimal qaScoreAverage) {
    }
}
