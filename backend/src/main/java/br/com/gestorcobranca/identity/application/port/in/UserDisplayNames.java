package br.com.gestorcobranca.identity.application.port.in;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Nomes de exibição de usuários da própria assessoria, para telas operacionais que mostram quem
 * fez algo (autor de interação, quem publicou uma política, quem anexou uma evidência). Devolve
 * <b>somente</b> id e nome — nunca e-mail, perfis, escopo ou situação — e nada de outras
 * assessorias (RLS). Ids inexistentes são omitidos, sem erro.
 */
public interface UserDisplayNames {

    /** Limite de ids por chamada. */
    int MAX_IDS = 100;

    /** @param userIds até {@value #MAX_IDS} ids (repetidos são ignorados) */
    List<UserName> resolve(Collection<UUID> userIds);

    record UserName(UUID id, String name) {
    }
}
