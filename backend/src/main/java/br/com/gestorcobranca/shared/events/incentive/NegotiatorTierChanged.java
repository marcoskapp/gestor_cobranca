package br.com.gestorcobranca.shared.events.incentive;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Nível de desempenho publicado (só o nível; nunca nota, quartil ou valores). tier nulo = sem nível (volta ao padrão).
 * <p>
 * Publicado pelo {@code incentive} depois da revisão humana da rodada de classificação
 * (docs/tecnico/desenho/distribuicao-por-desempenho.md §3.3 e §11.1); consumido pelo {@code collection} (projeção
 * {@code negotiator_tier}). Um evento por pessoa a cada publicação, mesmo sem mudança de nível, para a projeção ficar
 * idempotente pela {@code publicationId}.
 *
 * @param tier          {@code A}, {@code B}, {@code C} ou {@code D}; nulo = a pessoa perdeu {@code CASE_WORK} ou foi
 *                      desativada desde a publicação anterior
 * @param validFrom     data da publicação (fuso de operação), a partir da qual o nível vale
 * @param publicationId id da rodada de classificação publicada
 */
public record NegotiatorTierChanged(UUID eventId, UUID tenantId, Instant occurredAt, UUID userId, String tier,
                                    LocalDate validFrom, UUID publicationId) implements IntegrationEvent { }
