package br.com.gestorcobranca.portal.application.port.out;

import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.KeyType;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.State;

import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * Estado do bloqueio progressivo por chave (HMAC do documento ou do IP) no tenant corrente,
 * persistido para valer entre instâncias. As atualizações são serializadas por chave (lock de linha).
 */
public interface AccessThrottleStore {

    State current(KeyType type, String keyHash, Instant now);

    /** Aplica a transição (pura) sob lock e grava; devolve o novo estado. */
    State update(KeyType type, String keyHash, Instant now, UnaryOperator<State> transition);
}
