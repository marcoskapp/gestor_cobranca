package br.com.gestorcobranca.analytics.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Recorte efetivo de uma consulta, já com o escopo do usuário aplicado. Conjunto {@code null} =
 * sem restrição; conjunto vazio = nada corresponde. Registros sem credor (ou sem pessoa) só entram
 * quando a respectiva restrição é {@code null}.
 *
 * @param fromInstant início do período (00:00 do fuso de operação do dia {@code from})
 * @param toInstant   fim exclusivo (00:00 do dia seguinte a {@code to})
 */
public record Slice(LocalDate from, LocalDate to, Instant fromInstant, Instant toInstant, Set<UUID> creditors,
                    Set<UUID> portfolios, Set<UUID> operators, Set<String> channels) {
}
