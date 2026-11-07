package br.com.gestorcobranca.shared.paging;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ordenação pedida pela API ({@code sort=campo,asc|desc}), ainda sem validação do campo: cada caso de uso confere o
 * nome contra a própria lista branca ({@link SortableFields}) antes de chegar ao banco.
 *
 * @param field     nome público do campo (o da visão da API, nunca o da coluna ou da entidade)
 * @param ascending direção
 */
public record SortRequest(String field, boolean ascending) {

    /** Nome público: letras e dígitos em camelCase, até 40 caracteres (nada de caminhos, espaços ou expressões). */
    private static final Pattern FIELD = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,39}");

    public SortRequest {
        Objects.requireNonNull(field, "field");
    }

    /**
     * Lê {@code campo} ou {@code campo,asc|desc} (sem direção = crescente). Vazio ou nulo = sem ordenação pedida.
     *
     * @throws InvalidSortException formato inválido
     */
    public static SortRequest parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.strip().split(",", -1);
        if (parts.length > 2 || !FIELD.matcher(parts[0].strip()).matches()) {
            throw new InvalidSortException();
        }
        boolean ascending = true;
        if (parts.length == 2) {
            String direction = parts[1].strip().toLowerCase(Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw new InvalidSortException();
            }
            ascending = direction.equals("asc");
        }
        return new SortRequest(parts[0].strip(), ascending);
    }
}
