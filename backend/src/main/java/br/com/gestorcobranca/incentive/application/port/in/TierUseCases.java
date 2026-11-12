package br.com.gestorcobranca.incentive.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Classificação de desempenho do negociador (docs/tecnico/desenho/distribuicao-por-desempenho.md §3 e §11.2):
 * parâmetros ({@code TIER_MANAGE}), rodadas com revisão humana ({@code TIER_REVIEW}, pessoas no escopo
 * {@code PERFORMANCE_VIEW_TEAM/ALL}), publicação da rodada inteira (exige também {@code PERFORMANCE_VIEW_ALL}) e o
 * próprio nível ({@code PERFORMANCE_VIEW_OWN}). Só o nível sai do módulo ({@code NegotiatorTierChanged}).
 */
public interface TierUseCases {

    /** Parâmetros vigentes (os padrões quando nunca foram alterados). {@code TIER_MANAGE}. */
    SettingsView settings();

    /** Altera os parâmetros (valem para a próxima rodada). {@code TIER_MANAGE}. */
    SettingsView updateSettings(SettingsCommand command);

    /** Rodadas mais recentes primeiro (até 24), com contagens por nível das pessoas no escopo. */
    List<RoundSummary> rounds();

    /** Rodada com as pessoas no escopo de quem vê; acesso registrado no log de acesso. */
    RoundDetail round(UUID roundId);

    /**
     * Apura agora (janela até ontem) e abre a rodada para revisão; recusa ({@code tier_round.open_exists}) se já houver
     * rodada em revisão. {@code TIER_MANAGE}.
     */
    RoundSummary assessNow();

    /** Ajusta o nível final de uma pessoa da rodada em revisão, com motivo. {@code TIER_REVIEW} e pessoa no escopo. */
    PersonView adjust(UUID roundId, UUID userId, AdjustCommand command);

    /** Publica a rodada inteira: os níveis passam a valer hoje. {@code TIER_REVIEW} + {@code PERFORMANCE_VIEW_ALL}. */
    RoundSummary publish(UUID roundId);

    /** O próprio nível vigente e o que o compõe (quartis e base), sem posição nem colegas. */
    MyTierView mine();

    /** @param matrix qualidade ("Q4".."Q1") → eficiência ("Q4".."Q1") → nível (A–D) */
    record SettingsCommand(int windowDays, int minEvaluations, int minCases, BigDecimal qualityGate,
                           Map<String, Map<String, String>> matrix, String defaultTier, String periodicity,
                           int reviewBusinessDays) {
    }

    /** @param customized a assessoria já gravou parâmetros (senão, são os padrões do desenho) */
    record SettingsView(int windowDays, int minEvaluations, int minCases, BigDecimal qualityGate,
                        Map<String, Map<String, String>> matrix, String defaultTier, String periodicity,
                        int reviewBusinessDays, boolean customized) {
    }

    record AdjustCommand(String tier, String reason) {
    }

    /**
     * @param status     PROPOSED, PUBLISHED ou EXPIRED
     * @param origin     SCHEDULED (rotina) ou MANUAL ("Apurar agora")
     * @param people     pessoas da rodada no escopo de quem vê
     * @param tierCounts nível final → pessoas (no escopo de quem vê)
     * @param adjusted   pessoas com nível ajustado (no escopo)
     */
    record RoundSummary(UUID id, LocalDate periodFrom, LocalDate periodTo, String status, String origin,
                        LocalDate reviewDeadline, Instant createdAt, Instant publishedAt, LocalDate validFrom,
                        Instant expiredAt, int people, Map<String, Long> tierCounts, int adjusted) {
    }

    /**
     * @param settings   parâmetros com que a rodada foi apurada
     * @param canAdjust  a rodada está em revisão, no prazo, e o ator tem {@code TIER_REVIEW}
     * @param canPublish além disso, o ator tem escopo total de pessoas
     */
    record RoundDetail(RoundSummary round, SettingsView settings, List<PersonView> people, boolean canAdjust,
                       boolean canPublish) {
    }

    /**
     * @param qualityQuartile    1–4 (já com o portão); nulo = avaliações abaixo do mínimo
     * @param efficiencyQuartile 1–4; nulo = casos abaixo do mínimo ou sem valor esperado
     * @param qualityAverage     nota média da monitoria na janela (0–10)
     * @param recoveryRatio      realizado ÷ esperado das safras na janela
     * @param self               é o próprio ator (ninguém ajusta o próprio nível)
     */
    record PersonView(UUID userId, String name, Integer qualityQuartile, Integer efficiencyQuartile,
                      boolean qualityGateFailed, long evaluations, BigDecimal qualityAverage, long cases,
                      BigDecimal recoveryRatio, String proposedTier, String finalTier, boolean adjusted,
                      String adjustedByName, Instant adjustedAt, String adjustmentReason, boolean self) {
    }

    /**
     * @param status             NONE (nenhuma publicação), CLASSIFIED (na publicação vigente) ou NOT_CLASSIFIED
     *                           (publicação vigente sem a pessoa: vale o nível padrão)
     * @param tier               nível vigente (o padrão quando NONE/NOT_CLASSIFIED)
     * @param defaultTier        nível de quem não tem amostra suficiente
     * @param missingQuality     avaliações abaixo do mínimo
     * @param missingEfficiency  casos abaixo do mínimo
     * @param adjusted           o nível foi ajustado na revisão (o motivo fica com a supervisão)
     */
    record MyTierView(String status, String tier, String defaultTier, LocalDate validFrom, LocalDate periodFrom,
                      LocalDate periodTo, Integer qualityQuartile, Integer efficiencyQuartile,
                      boolean qualityGateFailed, boolean missingQuality, boolean missingEfficiency, long evaluations,
                      long cases, int minEvaluations, int minCases, BigDecimal qualityGate, boolean adjusted,
                      Map<String, Map<String, String>> matrix) {
    }
}
