package br.com.gestorcobranca.shared.events.scoring;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Recuperado × esperado de uma safra de score: casos escorados na data de referência, agrupados por
 * credor, carteira e responsável no momento do cálculo, depois de encerrada a janela de desfecho
 * ({@code outcomeDays}). Publicado pelo {@code scoring}; consumido pelo {@code incentive}
 * (indicador {@code financial.expectedRecoveryRatio}).
 * <p>
 * Idempotente por {@code cohortId} (determinístico para safra × credor × carteira × responsável):
 * uma republicação com os mesmos ids substitui os valores (ex.: estorno tardio corrigido).
 *
 * @param assigneeId       responsável humano do caso na data de referência (null = fila/assistente)
 * @param cases            casos da safra no grupo
 * @param expectedPayers   soma das probabilidades (casos pagantes esperados)
 * @param realizedPayers   casos com pagamento confirmado (líquido de estorno) na janela
 * @param expectedAmount   valor esperado: Σ probabilidade × saldo em aberto × fração paga típica do modelo
 * @param realizedAmount   valor confirmado na janela (líquido de estornos)
 */
public record ExpectedRecoveryMeasured(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID cohortId,
        LocalDate referenceDate,
        int outcomeDays,
        UUID creditorId,
        UUID portfolioId,
        UUID assigneeId,
        long cases,
        BigDecimal expectedPayers,
        long realizedPayers,
        BigDecimal expectedAmount,
        BigDecimal realizedAmount,
        UUID modelId,
        int modelVersion) implements IntegrationEvent {
}
