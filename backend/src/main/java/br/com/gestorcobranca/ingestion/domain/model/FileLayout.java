package br.com.gestorcobranca.ingestion.domain.model;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Como ler o conteúdo de um lote: formato, parâmetros do CSV/XLSX e colunas. É a forma
 * imutável de um {@link ImportLayout} (ou do layout canônico) entregue aos leitores de arquivo.
 *
 * @param layoutId  nulo para o layout canônico
 * @param sheetName aba do XLSX; nula = primeira aba
 */
public record FileLayout(UUID layoutId, String code, int version, ImportFormat format, char delimiter, String encoding,
                         boolean hasHeader, String sheetName, List<Column> columns) {

    /** Código do layout canônico (sem layout cadastrado pelo credor). */
    public static final String STANDARD_CODE = "PADRAO";
    public static final int STANDARD_VERSION = 1;
    public static final char STANDARD_DELIMITER = ';';
    public static final String STANDARD_ENCODING = "UTF-8";

    public FileLayout {
        columns = List.copyOf(columns);
    }

    /**
     * Layout canônico: cabeçalho com os nomes de {@link CanonicalField#column()}; CSV separado
     * por ponto e vírgula em UTF-8; XLSX na primeira aba; JSON com as mesmas chaves.
     */
    public static FileLayout standard(ImportFormat format) {
        List<Column> columns = Arrays.stream(CanonicalField.values())
                .map(f -> new Column(f, f.column(), null))
                .toList();
        return new FileLayout(null, STANDARD_CODE, STANDARD_VERSION, format, STANDARD_DELIMITER, STANDARD_ENCODING,
                true, null, columns);
    }

    /**
     * Coluna do layout: pelo cabeçalho (arquivo com cabeçalho) ou pela posição 1..n (sem cabeçalho).
     */
    public record Column(CanonicalField field, String header, Integer position) {
    }
}
