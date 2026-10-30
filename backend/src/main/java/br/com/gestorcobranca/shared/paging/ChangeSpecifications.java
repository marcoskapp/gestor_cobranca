package br.com.gestorcobranca.shared.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.UUID;

/**
 * Filtro e ordem da leitura incremental para entidades com {@code updatedAt}, {@code id} e {@code creditorId}
 * (as que estendem {@code BaseEntity} e pertencem a um credor). Usado pela camada de persistência de cada módulo dono
 * do dado, que combina com os próprios filtros e lê {@code limit + 1} linhas com {@link #page(ChangeQuery)}.
 */
public final class ChangeSpecifications {

    /** Ordem estável da sincronização: atualização e, no empate, id. */
    public static final Sort ORDER = Sort.by(Sort.Order.asc("updatedAt"), Sort.Order.asc("id"));

    private ChangeSpecifications() {
    }

    /** Primeira "página" de {@code limit + 1} linhas na ordem da sincronização (o cursor já está no filtro). */
    public static Pageable page(ChangeQuery q) {
        return PageRequest.of(0, q.limit() + 1, ORDER);
    }

    /**
     * Janela de atualização, cursor e restrição de credores.
     *
     * @param allowedCreditorIds credores visíveis; {@code null} = sem restrição, vazio = nenhum
     */
    public static <T> Specification<T> window(ChangeQuery q, Collection<UUID> allowedCreditorIds) {
        Specification<T> spec = Specification.unrestricted();
        if (allowedCreditorIds != null) {
            spec = spec.and((root, query, cb) -> allowedCreditorIds.isEmpty()
                    ? cb.disjunction() : root.get("creditorId").in(allowedCreditorIds));
        }
        if (q.updatedSince() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("updatedAt"), q.updatedSince()));
        }
        if (q.updatedBefore() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("updatedAt"), q.updatedBefore()));
        }
        if (q.after() != null) {
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.greaterThan(root.get("updatedAt"), q.after().updatedAt()),
                    cb.and(cb.equal(root.get("updatedAt"), q.after().updatedAt()),
                            cb.greaterThan(root.get("id"), q.after().id()))));
        }
        return spec;
    }
}
