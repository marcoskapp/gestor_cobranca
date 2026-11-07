package br.com.gestorcobranca.shared.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Paginação recebida da API com limites seguros (tamanho máximo 200), para evitar consultas
 * que tragam volumes excessivos de dados pessoais de uma só vez.
 *
 * @param sort ordenação pedida pelo cliente (nula = a padrão do caso de uso); só vale nas consultas que declaram uma
 *             lista branca ({@link #toPageable(SortableFields, Sort)}) — as demais ignoram o pedido
 */
public record PageQuery(int page, int size, SortRequest sort) {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 20;

    public PageQuery {
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public PageQuery(int page, int size) {
        this(page, size, null);
    }

    public static PageQuery of(Integer page, Integer size) {
        return of(page, size, null);
    }

    /**
     * Paginação com ordenação no formato {@code campo,asc|desc}.
     *
     * @throws InvalidSortException formato inválido (o campo é conferido pelo caso de uso)
     */
    public static PageQuery of(Integer page, Integer size, String sort) {
        return new PageQuery(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size, SortRequest.parse(sort));
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }

    public Pageable toPageable() {
        return PageRequest.of(page, size);
    }

    /**
     * Página com a ordenação pedida, conferida contra a lista branca da consulta, ou a padrão quando nada foi pedido.
     *
     * @throws InvalidSortException campo fora da lista branca
     */
    public Pageable toPageable(SortableFields fields, Sort defaultSort) {
        return PageRequest.of(page, size, fields.resolve(sort, defaultSort));
    }
}
