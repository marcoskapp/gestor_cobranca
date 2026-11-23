package br.com.gestorcobranca.shared.events.incentive;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * O valor a pagar de extratos de comissão do período mudou: extratos gerados no fechamento, recalculados ou com ajuste
 * aplicado (docs/tecnico/desenho/remuneracao-variavel.md, dependências). Só o período — nunca pessoa, extrato ou valor.
 * <p>
 * Publicado pelo {@code incentive}; consumido pelo {@code costing}, que reapura a comissão variável da margem
 * (margem-por-carteira.md §4.2): a estimativa dos dias do período vira o valor do extrato sem "Reapurar o período".
 *
 * @param periodStart 1º dia do período de comissão (mês) cujos extratos mudaram
 */
public record CommissionStatementsChanged(UUID eventId, UUID tenantId, Instant occurredAt, LocalDate periodStart)
        implements IntegrationEvent { }
