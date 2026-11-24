package br.com.gestorcobranca.identity.application.port.out;

import br.com.gestorcobranca.shared.security.ThrottlePolicy.State;

import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * Estado persistido do limite de login do portal do credor por IP (HMAC do IP; tabela com RLS por tenant), serializado
 * entre instâncias. A regra é {@code shared.security.ThrottlePolicy}.
 */
public interface CreditorLoginThrottle {

    State update(String ipKeyHash, Instant now, UnaryOperator<State> transition);

    /** Remove, em lotes, o estado sem tentativa desde {@code cutoff} e sem bloqueio vigente (retenção do HMAC do IP). */
    int deleteIdleBefore(Instant cutoff, Instant now, int limit);
}
