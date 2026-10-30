package br.com.gestorcobranca.shared.paging;

import java.time.Instant;

/**
 * Consulta incremental (sincronização) por data de atualização com paginação por cursor.
 *
 * @param updatedSince  só registros atualizados a partir deste instante (inclusive); nulo = desde o início
 * @param updatedBefore só registros atualizados antes deste instante (exclusivo) — janela de estabilização que evita
 *                      pular registros de transações ainda não confirmadas; nulo = sem limite
 * @param after         cursor do último registro já lido (exclusivo); nulo = primeira página
 * @param limit         tamanho da página (1 a {@value #MAX_LIMIT})
 */
public record ChangeQuery(Instant updatedSince, Instant updatedBefore, ChangeCursor after, int limit) {

    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 100;

    public ChangeQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }
}
