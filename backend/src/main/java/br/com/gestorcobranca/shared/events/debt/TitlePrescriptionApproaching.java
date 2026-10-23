package br.com.gestorcobranca.shared.events.debt;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A prescrição estimada de um título em cobrança entrou na janela de alerta
 * ({@code app.debt.prescription-alert-days}). Publicado uma vez por data estimada: só volta a ser
 * publicado se uma interrupção ou mudança de regra alterar a data. Insumo para a elegibilidade
 * jurídica (módulo legal) e priorização da cobrança.
 *
 * @param instrumentType natureza do contrato (nome do enum {@code InstrumentType})
 * @param daysRemaining  dias até a data estimada na data do alerta
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
        LocalDate estimatedDate,
        long daysRemaining,
        BigDecimal openBalance) implements IntegrationEvent {
}
