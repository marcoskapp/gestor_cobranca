package br.com.gestorcobranca.payment.application.port.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Nome dos tokens de integração que registraram pagamentos (canal {@code INTEGRATION}, H07). Implementada pelo
 * publicapi; o payment só a chama com ids que já estão nos pagamentos visíveis para quem consulta (o escopo por credor
 * é do payment). Nunca devolve segredo: só o nome, o prefixo de exibição e a situação do token no momento da consulta
 * (ativo, pausado pela plataforma, revogado ou expirado). O nome é o atual (editável no cadastro do token).
 */
public interface IntegrationTokenLabels {

    /** Rótulos dos tokens pedidos; ids que não são de token (ou de outra assessoria) ficam fora do mapa. */
    Map<UUID, TokenLabel> labels(Collection<UUID> tokenIds);

    /**
     * @param revoked revogado (mantido para quem só precisa saber disso; igual a {@code state == REVOKED})
     * @param state   situação efetiva do token agora
     */
    record TokenLabel(UUID id, String name, String displayPrefix, boolean revoked, State state) {

        public TokenLabel {
            // obrigatório: uma implementação que não informe a situação não pode fazer o token valer como ativo e
            // dispensar a confirmação da aprovação (decisão 1631)
            java.util.Objects.requireNonNull(state, "state");
        }

        public boolean active() {
            return state == State.ACTIVE;
        }
    }

    /** Situação efetiva do token: ativo, pausado pela plataforma (reversível), revogado ou expirado (definitivos). */
    enum State {
        ACTIVE,
        PAUSED,
        REVOKED,
        EXPIRED
    }
}
