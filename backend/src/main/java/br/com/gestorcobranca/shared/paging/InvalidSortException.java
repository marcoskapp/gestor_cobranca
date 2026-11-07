package br.com.gestorcobranca.shared.paging;

import br.com.gestorcobranca.shared.domain.DomainException;

/**
 * Ordenação pedida fora da lista branca do caso de uso ou em formato inválido (HTTP 400, {@code request.invalid_sort}).
 * A mensagem não repete o valor recebido (vem do cliente e iria para o log).
 */
public class InvalidSortException extends DomainException {

    public static final String CODE = "request.invalid_sort";

    public InvalidSortException() {
        super(CODE, "Ordenação inválida para esta consulta");
    }
}
