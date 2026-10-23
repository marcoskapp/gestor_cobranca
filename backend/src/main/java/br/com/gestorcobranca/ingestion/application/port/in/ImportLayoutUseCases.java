package br.com.gestorcobranca.ingestion.application.port.in;

import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Layouts de importação por credor (opcionalmente restritos a uma carteira), versionados.
 * Publicar um código existente cria a próxima versão e desativa a anterior; lotes guardam a
 * versão usada. O layout canônico (código {@code PADRAO}) é fixo e documentado em
 * {@code docs/negocio/layout-importacao.md}.
 */
public interface ImportLayoutUseCases {

    ImportLayoutView publish(PublishLayoutCommand command);

    ImportLayoutView get(UUID layoutId);

    List<ImportLayoutView> list(UUID creditorId, boolean includeInactive);

    ImportLayoutView deactivate(UUID layoutId);

    /** Campos canônicos (colunas do layout padrão). */
    CanonicalLayoutView canonicalLayout();

    /**
     * Modelo vazio (somente cabeçalho) do layout canônico para preenchimento pelo credor.
     *
     * @param format CSV ou XLSX
     */
    void writeTemplate(String format, OutputStream output);

    /**
     * @param format    CSV ou XLSX
     * @param delimiter CSV: ";", ",", "|" ou "\t"
     * @param encoding  CSV: UTF-8, ISO-8859-1 ou WINDOWS-1252
     * @param sheetName XLSX: aba (nula = primeira)
     * @param portfolioId restringe o layout a uma carteira do credor (opcional)
     */
    record PublishLayoutCommand(UUID creditorId, UUID portfolioId, String code, String name, String format, String delimiter,
                                String encoding, boolean hasHeader, String sheetName, List<ColumnCommand> columns) {
    }

    /** @param field nome canônico (ex.: {@code documento}); informe {@code header} ou {@code position} */
    record ColumnCommand(String field, String header, Integer position) {
    }

    record ImportLayoutView(UUID id, UUID creditorId, UUID portfolioId, String code, int version, String name, String format,
                            String delimiter, String encoding, boolean hasHeader, String sheetName,
                            List<ColumnCommand> columns, boolean active, Instant createdAt) {
    }

    /** @param reservedPrefixes prefixos de colunas previstas para versão futura (hoje ignoradas com aviso) */
    record CanonicalLayoutView(String code, int version, String delimiter, String encoding, List<CanonicalFieldView> fields,
                               List<String> reservedPrefixes) {
    }

    record CanonicalFieldView(String field, boolean required, boolean personal) {
    }
}
