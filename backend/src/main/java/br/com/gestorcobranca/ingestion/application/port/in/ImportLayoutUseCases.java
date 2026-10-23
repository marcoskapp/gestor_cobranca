package br.com.gestorcobranca.ingestion.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Layouts de importação por credor, versionados. Publicar um código existente cria a próxima
 * versão e desativa a anterior; lotes guardam a versão usada.
 */
public interface ImportLayoutUseCases {

    ImportLayoutView publish(PublishLayoutCommand command);

    ImportLayoutView get(UUID layoutId);

    List<ImportLayoutView> list(UUID creditorId, boolean includeInactive);

    ImportLayoutView deactivate(UUID layoutId);

    /** Campos canônicos (colunas do layout padrão). */
    List<CanonicalFieldView> canonicalFields();

    /**
     * @param format    CSV ou XLSX
     * @param delimiter CSV: ";", ",", "|" ou "\t"
     * @param encoding  CSV: UTF-8, ISO-8859-1 ou WINDOWS-1252
     * @param sheetName XLSX: aba (nula = primeira)
     */
    record PublishLayoutCommand(UUID creditorId, String code, String name, String format, String delimiter,
                                String encoding, boolean hasHeader, String sheetName, List<ColumnCommand> columns) {
    }

    /** @param field nome canônico (ex.: {@code documento}); informe {@code header} ou {@code position} */
    record ColumnCommand(String field, String header, Integer position) {
    }

    record ImportLayoutView(UUID id, UUID creditorId, String code, int version, String name, String format,
                            String delimiter, String encoding, boolean hasHeader, String sheetName,
                            List<ColumnCommand> columns, boolean active, Instant createdAt) {
    }

    record CanonicalFieldView(String field, boolean required, boolean personal) {
    }
}
