package br.com.gestorcobranca.telephony.application.port.in;

import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;

import java.util.UUID;

/**
 * <strong>Uso interno dos adaptadores de provedor (webhook e simulador).</strong> Aplica um evento
 * de chamada já autenticado à conta, no tenant corrente: idempotente pelo id do evento, ignora
 * eventos obsoletos (estado anterior ao atual) e de chamadas desconhecidas.
 *
 * @return {@code applied}, {@code duplicate}, {@code stale}, {@code unknown_call} ou {@code ignored}
 */
public interface ProviderEventUseCases {

    String apply(UUID accountId, ProviderCallEvent event);
}
