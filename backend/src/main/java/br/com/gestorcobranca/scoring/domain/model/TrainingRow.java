package br.com.gestorcobranca.scoring.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Observação de treino/validação: snapshot de um caso numa data de referência com o desfecho já
 * apurado.
 *
 * @param paid       houve pagamento confirmado (líquido de estorno) na janela após a referência
 * @param paidAmount valor confirmado na janela
 */
public record TrainingRow(UUID caseId, LocalDate referenceDate, FeatureVector features, boolean paid,
                          BigDecimal openBalance, BigDecimal paidAmount) {
}
