package br.com.gestorcobranca.sandbox.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Caixa de e-mail do sandbox (Mailpit) vista pelo lado do credor: o endereço de resposta ({@code Reply-To}) do pedido
 * de contestação recebido, como o credor o veria ao responder. Nenhum token é obtido por outro caminho.
 */
public interface DisputeReplyMailbox {

    /**
     * Endereço de resposta do e-mail mais recente ao destinatário com o protocolo no assunto, enviado depois de
     * {@code sentAfter}; vazio se não chegar no prazo.
     */
    Optional<String> awaitReplyAddress(String to, String protocol, Instant sentAfter, Duration timeout);
}
