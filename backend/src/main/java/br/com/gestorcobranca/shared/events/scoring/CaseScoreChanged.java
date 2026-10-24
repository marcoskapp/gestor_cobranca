package br.com.gestorcobranca.shared.events.scoring;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Score de propensão à recuperação de um caso de cobrança mudou (primeiro cálculo, mudança de
 * faixa ou variação mínima configurada). Publicado pelo módulo {@code scoring}; o
 * {@code collection} guarda o score no caso (filas com ordem {@code HIGHEST_SCORE} e critério de
 * faixa de score) e o {@code campaign} na projeção de audiência (filtro de score dos segmentos).
 * <p>
 * Uso permitido: <b>somente priorização operacional</b> (ordem de atendimento, seleção de
 * público). Nunca para negar atendimento nem para mudar condição de negociação
 * ({@code docs/negocio/score-propensao.md}).
 *
 * @param score         0–1000: probabilidade estimada × 1000 de pagamento confirmado em
 *                      {@code outcomeDays} dias
 * @param band          faixa A (maior propensão) a E
 * @param previousScore score publicado anteriormente (null no primeiro)
 * @param previousBand  faixa anterior (null no primeiro)
 * @param modelId       versão de modelo que calculou
 * @param modelKind     SCORECARD ou LOGISTIC_REGRESSION
 * @param calculatedAt  instante do cálculo: consumidores aplicam só o cálculo mais recente
 *                      (entregas fora de ordem ou republicadas são ignoradas)
 */
public record CaseScoreChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        int score,
        String band,
        Integer previousScore,
        String previousBand,
        UUID modelId,
        int modelVersion,
        String modelKind,
        int outcomeDays,
        Instant calculatedAt) implements IntegrationEvent {
}
