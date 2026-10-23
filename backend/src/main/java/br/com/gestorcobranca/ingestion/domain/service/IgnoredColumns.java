package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Avisos de colunas do arquivo (ou chaves da API) que o layout não usa. Nunca impedem a carga:
 * o layout é extensível, e arquivos com colunas a mais continuam válidos.
 * <ul>
 *   <li>{@code import.columns_not_supported}: colunas com prefixo jurídico (garantias, avaliações,
 *       coobrigados, natureza do título — {@code docs/design/juridico.md}, A.4) que o layout não
 *       reconhece, como um terceiro coobrigado ou uma segunda garantia na mesma linha.</li>
 *   <li>{@code import.columns_ignored}: demais colunas não mapeadas.</li>
 * </ul>
 * Um aviso por lote (linha 0), com até {@value #MAX_LISTED} nomes; os nomes passam pelo
 * mascaramento de dados pessoais.
 */
public final class IgnoredColumns {

    public static final int MAX_LISTED = 20;
    private static final int NAME_MAX = 40;
    /** Prefixos (normalizados) das colunas jurídicas: as não reconhecidas recebem aviso próprio. */
    static final List<String> RESERVED_PREFIXES = List.of("garantia", "avaliacao", "coobrigado", "natureza_titulo",
            "titulo_executivo");

    private IgnoredColumns() {
    }

    public static List<String> reservedPrefixes() {
        return RESERVED_PREFIXES;
    }

    public static boolean isReserved(String column) {
        String key = CanonicalField.normalizeHeader(column).replace(' ', '_');
        return RESERVED_PREFIXES.stream().anyMatch(key::startsWith);
    }

    public static List<RowIssue> issues(Collection<String> columns) {
        List<String> reserved = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            (isReserved(column) ? reserved : other).add(display(column));
        }
        List<RowIssue> issues = new ArrayList<>();
        if (!reserved.isEmpty()) {
            issues.add(RowIssue.of(0, IssueSeverity.WARNING, null, "import.columns_not_supported",
                    "Colunas de garantias, avaliações, coobrigados ou natureza do título não reconhecidas pelo layout "
                            + "(ex.: terceiro coobrigado ou segunda garantia), ignoradas nesta carga: " + list(reserved)));
        }
        if (!other.isEmpty()) {
            issues.add(RowIssue.of(0, IssueSeverity.WARNING, null, "import.columns_ignored",
                    "Colunas não mapeadas no layout, ignoradas: " + list(other)));
        }
        return issues;
    }

    private static String display(String column) {
        String value = column.strip();
        value = value.length() > NAME_MAX ? value.substring(0, NAME_MAX) : value;
        return SensitiveDataMasker.mask(value);
    }

    private static String list(List<String> names) {
        String listed = String.join(", ", names.subList(0, Math.min(MAX_LISTED, names.size())));
        return names.size() > MAX_LISTED ? listed + " (e mais %d)".formatted(names.size() - MAX_LISTED) : listed;
    }
}
