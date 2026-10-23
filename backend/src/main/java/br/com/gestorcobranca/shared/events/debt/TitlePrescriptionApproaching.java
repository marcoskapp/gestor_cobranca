package br.com.gestorcobranca.shared.events.debt;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A prescrição estimada de uma pretensão de um título em cobrança entrou na janela de alerta da
 * regra do tenant ({@code debt.prescription_rule.alert_days}). Publicado uma vez por data
 * estimada: só volta a ser publicado se uma interrupção, reclassificação ou mudança de regra
 * alterar a data. Insumo para a elegibilidade jurídica (módulo legal) e a priorização da cobrança.
 *
 * @param instrumentType natureza do contrato (nome do enum {@code InstrumentType})
 * @param claim          EXECUTION (pretensão executiva) ou COLLECTION (cobrança/monitória)
 * @param daysRemaining  dias até a data estimada na data do alerta
 * @param ruleValidated  falso se a regra aplicada ainda está pendente de validação jurídica
 * @param openBalance    saldo em aberto do título
 */
public record TitlePrescriptionApproaching(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID titleId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID originContractId,
        String instrumentType,
        boolean executiveTitle,
        String claim,
        LocalDate estimatedDate,
        long daysRemaining,
        boolean ruleValidated,
        BigDecimal openBalance) implements IntegrationEvent {
}
