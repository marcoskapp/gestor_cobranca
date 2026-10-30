package br.com.gestorcobranca.shared.paging;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Página de uma leitura incremental (sincronização).
 *
 * @param items registros em ordem crescente de ({@code updatedAt}, {@code id}), cada um com o instante da última
 *              alteração e a versão (lock otimista) — insumo de {@code ETag} e do próximo {@code updatedSince}
 * @param next  cursor para a próxima página; nulo quando não há mais registros na janela consultada
 */
public record ChangePage<T>(List<Entry<T>> items, ChangeCursor next) {

    public ChangePage {
        items = List.copyOf(items);
    }

    /** Registro alterado com os metadados de sincronização. */
    public record Entry<T>(T value, Instant updatedAt, long version) {
    }

    public <R> ChangePage<R> map(Function<T, R> mapper) {
        return new ChangePage<>(items.stream().map(e -> new Entry<>(mapper.apply(e.value()), e.updatedAt(), e.version()))
                .toList(), next);
    }

    /**
     * Monta a página a partir de {@code limit + 1} linhas lidas na ordem da sincronização: a linha excedente só indica
     * que há mais.
     *
     * @param updatedAt instante da última alteração da linha
     * @param id        id da linha (desempate do cursor)
     * @param version   versão da linha
     * @param view      conversão da linha na visão do caso de uso
     */
    public static <E, T> ChangePage<T> of(List<E> rowsPlusOne, int limit, Function<E, Instant> updatedAt,
                                          Function<E, java.util.UUID> id, Function<E, Long> version,
                                          Function<E, T> view) {
        List<E> page = rowsPlusOne.size() > limit ? rowsPlusOne.subList(0, limit) : rowsPlusOne;
        List<Entry<T>> entries = page.stream()
                .map(e -> new Entry<>(view.apply(e), updatedAt.apply(e), version.apply(e) == null ? 0L : version.apply(e)))
                .toList();
        ChangeCursor next = rowsPlusOne.size() > limit
                ? new ChangeCursor(updatedAt.apply(page.getLast()), id.apply(page.getLast())) : null;
        return new ChangePage<>(entries, next);
    }
}
