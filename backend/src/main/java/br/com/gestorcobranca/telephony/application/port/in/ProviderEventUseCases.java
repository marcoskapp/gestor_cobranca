package br.com.gestorcobranca.telephony.application.port.in;

import br.com.gestorcobranca.telephony.application.port.out.CallInstruction;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.InstructionRequest;

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

    /**
     * Instruções da chamada para o provedor que as pede por webhook (TwiML/Plivo XML), a partir do
     * estado atual da chamada: discar para o devedor (operador atendeu), aguardar na linha (AMD),
     * conectar ao operador escolhido, avisar o devedor ou encerrar. Chamada desconhecida → encerrar.
     */
    CallInstruction instruction(UUID accountId, InstructionRequest request);
}
