package br.com.gestorcobranca.shared.paging;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Busca por nome e paginação em memória de uma lista já filtrada pela regra de elegibilidade (ex.: usuários atribuíveis
 * a um campo). O texto buscado não diferencia maiúsculas nem acentos; a ordem é por nome e, no empate, por id.
 */
public final class NameSearch {

    private NameSearch() {
    }

    /**
     * @param candidates  itens elegíveis (já filtrados)
     * @param query       trecho do nome; vazio ou nulo = todos
     * @param ids         quando informado e não vazio, só esses ids (resolução do que já está escolhido)
     * @param maxPageSize teto do tamanho da página para esta consulta
     */
    public static <T, R> PageResponse<R> page(Collection<T> candidates, Function<T, UUID> id, Function<T, String> name,
                                              String query, Set<UUID> ids, PageQuery page, int maxPageSize,
                                              Function<T, R> mapper) {
        int size = Math.min(page.size(), maxPageSize);
        String needle = normalize(query);
        List<T> all = candidates.stream()
                .filter(t -> ids == null || ids.isEmpty() || ids.contains(id.apply(t)))
                .filter(t -> needle.isEmpty() || normalize(name.apply(t)).contains(needle))
                .sorted(Comparator.comparing((T t) -> normalize(name.apply(t))).thenComparing(id))
                .toList();
        int from = (int) Math.min((long) page.page() * size, all.size());
        List<R> content = all.subList(from, Math.min(from + size, all.size())).stream().map(mapper).toList();
        return new PageResponse<>(content, page.page(), size, all.size(), (all.size() + size - 1) / size);
    }

    /** Mesma busca, devolvendo os próprios itens. */
    public static <T> PageResponse<T> page(Collection<T> candidates, Function<T, UUID> id, Function<T, String> name,
                                           String query, Set<UUID> ids, PageQuery page, int maxPageSize) {
        return page(candidates, id, name, query, ids, page, maxPageSize, Function.identity());
    }

    /** Minúsculas e sem acentos, para buscar "Joao" e achar "João". */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
