package br.com.gestorcobranca.shared.paging;

import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lista branca de ordenação de uma consulta paginada: nome público (o da visão da API) → propriedade da entidade. Só
 * esses nomes chegam ao banco; qualquer outro é recusado com {@link InvalidSortException} (HTTP 400), para que o
 * cliente não ordene por coluna interna, sem índice ou que revele dado (ex.: documento cifrado).
 * <p>
 * A ordem pedida é sempre desempatada pelo {@code id} (paginação estável). Um campo pode ser <em>invertido</em>: o
 * atraso em dias cresce quando a data do vencimento mais antigo diminui, então {@code daysOverdue,desc} ordena por
 * {@code oldestDueDate} crescente.
 */
public final class SortableFields {

    private record Target(String property, boolean inverted) {
    }

    private final Map<String, Target> fields;

    private SortableFields(Map<String, Target> fields) {
        this.fields = Map.copyOf(fields);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Nomes públicos aceitos (documentação e testes). */
    public Set<String> names() {
        return fields.keySet();
    }

    /**
     * Ordenação a aplicar: a pedida (validada) + {@code id}, ou a padrão do caso de uso quando nada foi pedido.
     *
     * @throws InvalidSortException campo fora da lista branca
     */
    public Sort resolve(SortRequest request, Sort defaultSort) {
        Objects.requireNonNull(defaultSort, "defaultSort");
        if (request == null) {
            return defaultSort;
        }
        Target target = fields.get(request.field());
        if (target == null) {
            throw new InvalidSortException();
        }
        boolean ascending = request.ascending() != target.inverted();
        Sort.Order order = ascending ? Sort.Order.asc(target.property()) : Sort.Order.desc(target.property());
        return Sort.by(order).and(Sort.by("id"));
    }

    public static final class Builder {

        private final Map<String, Target> fields = new LinkedHashMap<>();

        private Builder() {
        }

        /** Campo público que ordena pela propriedade da entidade na mesma direção. */
        public Builder field(String name, String property) {
            return put(name, property, false);
        }

        /** Campo público que ordena pela propriedade na direção oposta (ex.: atraso × vencimento mais antigo). */
        public Builder inverted(String name, String property) {
            return put(name, property, true);
        }

        private Builder put(String name, String property, boolean inverted) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(property, "property");
            if (fields.putIfAbsent(name, new Target(property, inverted)) != null) {
                throw new IllegalArgumentException("Campo de ordenação repetido: " + name);
            }
            return this;
        }

        public SortableFields build() {
            return new SortableFields(fields);
        }
    }
}
