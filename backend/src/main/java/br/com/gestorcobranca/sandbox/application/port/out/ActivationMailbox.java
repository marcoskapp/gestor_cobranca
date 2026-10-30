package br.com.gestorcobranca.sandbox.application.port.out;

import java.time.Duration;
import java.time.Instant;

/**
 * Caixa de e-mail do sandbox (Mailpit): de onde a carga lê o link de ativação enviado ao usuário, exatamente como
 * uma pessoa faria ao abrir o e-mail. Nenhum token é obtido por outro caminho.
 */
public interface ActivationMailbox {

    /**
     * Token do link de ativação/redefinição mais recente enviado ao endereço depois de {@code sentAfter}.
     *
     * @throws IllegalStateException se nenhum e-mail chegar dentro do prazo
     */
    String awaitCredentialToken(String email, Instant sentAfter, Duration timeout);
}
