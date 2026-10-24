package br.com.gestorcobranca.scoring.domain.model;

/**
 * Ciclo de uma versão de modelo:
 * <pre>
 *   treino aprovado na validação / scorecard criado → CHALLENGER ──promoção manual──► CHAMPION ──nova promoção──► RETIRED
 *                                                        ├── rejeição manual ──► REJECTED
 *                                                        └── desafiante mais novo ──► SUPERSEDED
 *   treino reprovado na validação → FAILED_VALIDATION (nunca promovível)
 * </pre>
 * No máximo um campeão e um desafiante por assessoria (índices únicos parciais). O desafiante é
 * calculado em paralelo (sombra) nos snapshots, sem efeito na operação.
 */
public enum ModelStatus {
    CHALLENGER, CHAMPION, RETIRED, REJECTED, SUPERSEDED, FAILED_VALIDATION
}
