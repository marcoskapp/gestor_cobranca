package br.com.gestorcobranca.incentive.application.port.in;

import java.util.UUID;

/**
 * Quem pode ser escolhido num campo de pessoa do incentivo (negociador avaliado, ocorrência de conduta, meta
 * individual): <b>somente</b> id e nome de exibição — nunca e-mail, perfis, escopo ou situação.
 */
public record AssignableUserView(UUID id, String name) {

    /** Limite de resultados por página. */
    public static final int MAX_PAGE_SIZE = 50;

    /** Limite de ids na resolução de nomes já escolhidos. */
    public static final int MAX_IDS = 50;
}
