package br.com.gestorcobranca.costing.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Base de um aviso da apuração (linha de {@code costing.cost_warning_daily}). {@code quantity}: horas
 * ({@code HOURS_WITHOUT_BAND}), dólares sem câmbio do mês ({@code MISSING_FX_RATE}/{@code NO_FX_RATE}), unidades sem
 * tarifa ({@code MISSING_TARIFF}) ou custos fixos ({@code OVERHEAD_WITHOUT_BASIS}).
 */
public record CostWarning(LocalDate day, UUID creditorId, UUID portfolioId, CostWarningCode code, BigDecimal quantity) {
}
