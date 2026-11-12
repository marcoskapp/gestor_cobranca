package br.com.gestorcobranca.scoring.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inteligência de contato ({@code docs/tecnico/desenho/inteligencia-de-contato.md}): qual contato, canal e horário
 * respondem melhor, e depois de quantos contatos o devedor fecha e cumpre o acordo. <b>Só orienta</b>: nada aqui
 * muda a política de contato, o discador ou as réguas. Leitura com {@code SCORING_VIEW}; fatos de credor fora do
 * escopo do usuário nunca entram na resposta.
 */
public interface ContactIntelligenceUseCases {

    /**
     * Perfil de contato do devedor (visível no escopo; 404 fora dele). Com {@code caseId} (caso do devedor, visível),
     * só os fatos do credor do caso e a taxa da carteira do caso como referência; sem ele, os credores do escopo.
     */
    ContactProfileView profile(UUID debtorId, UUID caseId);

    /**
     * Painel da carteira no período (datas no fuso de operação, até 400 dias), filtrado pelo escopo de credores.
     *
     * @param personType INDIVIDUAL, COMPANY ou nulo (todos)
     */
    ContactInsightsView insights(LocalDate from, LocalDate to, UUID creditorId, UUID portfolioId, String personType);

    /**
     * @param minAttempts tentativas a partir das quais o perfil aparece ({@code sufficient})
     * @param emptyReason o que falta para o perfil aparecer (nulo quando suficiente)
     * @param contacts    contatos ordenados pela taxa suavizada (maior primeiro)
     * @param zone        fuso das faixas horárias
     */
    record ContactProfileView(UUID debtorId, UUID caseId, int totalAttempts, int minAttempts, boolean sufficient,
                              String emptyReason, String preferredChannel, String preferredChannelExplanation,
                              List<ChannelView> channels, List<ContactView> contacts,
                              BigDecimal portfolioMedianAttemptsToFirstSuccess, String notice, String zone) {
    }

    /** @param rate taxa suavizada de sucesso das tentativas (0–1) */
    record ChannelView(String channel, int attempts, int successes, int debtorInitiated, int reads, BigDecimal rate,
                       String explanation) {
    }

    /**
     * @param contactId  contato do devedor (nulo = fatos sem contato identificado)
     * @param confidence LOW (menos de 5 tentativas), MEDIUM (5–19) ou HIGH (20 ou mais)
     */
    record ContactView(UUID contactId, String channel, int attempts, int successes, int debtorInitiated, int reads,
                       BigDecimal rate, String confidence, SlotView bestSlot, Integer attemptsToFirstSuccess,
                       Instant lastSuccessAt, String explanation, String bestSlotExplanation) {
    }

    record SlotView(int startHour, int endHour, int attempts, int successes, BigDecimal rate) {
    }

    /**
     * @param minAttempts tentativas mínimas para uma célula ou linha exibir taxa
     */
    record ContactInsightsView(LocalDate from, LocalDate to, UUID creditorId, UUID portfolioId, String personType,
                               int minAttempts, Totals totals, List<HeatCellView> heatmap,
                               List<FirstSuccessView> firstSuccessCurve, long neverAnswered,
                               List<ChannelInsightView> channels, List<AgreementBucketView> agreementsByAttempts,
                               BigDecimal medianAttemptsToAgreement, BigDecimal medianAttemptsToCompletedAgreement,
                               BigDecimal medianDaysFirstContactToAgreement, List<PersonTypeView> byPersonType,
                               List<ContactOriginView> byContactOrigin, String notice) {
    }

    /** @param fulfillmentRate cumpridos ÷ (cumpridos + quebrados); em vigor e cancelados ficam fora */
    record Totals(long attempts, long successes, BigDecimal rate, long debtorInitiated, long agreements, long completed,
                  long broken, BigDecimal fulfillmentRate) {
    }

    /** @param weekday 1 = segunda … 7 = domingo */
    record HeatCellView(int weekday, int startHour, long attempts, long successes, BigDecimal rate) {
    }

    /** @param attempts posição da 1ª tentativa atendida (11 = 11 ou mais) */
    record FirstSuccessView(int attempts, long contacts, BigDecimal cumulativeShare) {
    }

    record ChannelInsightView(String channel, long attempts, long successes, BigDecimal rate, long debtorInitiated,
                              long reads) {
    }

    /** @param bucket faixa de tentativas antes do acordo: 0-1, 2-3, 4-6, 7-10, 11-20, 21+ */
    record AgreementBucketView(String bucket, long agreements, long completed, long broken, long active, long cancelled,
                               BigDecimal fulfillmentRate) {
    }

    /**
     * Contato novo (recebido em carga depois do cadastro do devedor) × antigo: taxa de sucesso das tentativas.
     *
     * @param origin NEW ou EXISTING
     * @param rate   nulo abaixo do mínimo de tentativas
     */
    record ContactOriginView(String origin, long attempts, long successes, BigDecimal rate) {
    }

    record PersonTypeView(String personType, long attempts, long successes, BigDecimal rate, long agreements,
                          long completed, long broken, BigDecimal fulfillmentRate) {
    }
}
