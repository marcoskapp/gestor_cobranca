package br.com.gestorcobranca.shared.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Paginação recebida da API com limites seguros (tamanho máximo 200), para evitar consultas
 * que tragam volumes excessivos de dados pessoais de uma só vez.
 */
public record PageQuery(int page, int size) {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 20;

    public PageQuery {
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }

    public Pageable toPageable() {
        return PageRequest.of(page, size);
    }
}
