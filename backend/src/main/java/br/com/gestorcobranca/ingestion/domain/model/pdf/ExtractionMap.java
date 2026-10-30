package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.List;
import java.util.Objects;

/**
 * Mapa de extração de um layout de PDF (versão de esquema {@value #SCHEMA_VERSION}): descreve <strong>onde</strong>
 * estão os dados no documento, nunca os valores. Proposto pela IA a partir do texto posicionado (ou escrito/ajustado
 * pelo usuário) e aplicado de forma determinística pelo intérprete ({@code MapInterpreter}): todo valor importado sai
 * do texto do PDF. Serializado em JSON (coluna {@code jsonb}, schema em {@code ingestion/pdf/extraction-map.schema.json}).
 * <p>
 * Destinos ({@code target}): nome de coluna do layout canônico ({@code documento}, {@code valor_principal}…),
 * {@value #INFO_PREFIX}{@code <nome>} (informação adicional, guardada na importação e não enviada ao lote) ou
 * {@value #PAID_WHEN_FILLED} (coluna de data de pagamento: preenchida → {@code situacao = PAGO}). Em totais de
 * controle, {@value #COUNT_TARGET} indica a quantidade de títulos.
 *
 * @param documentType          descrição livre do tipo de documento (ex.: "Histórico financeiro por cliente")
 * @param numberFormat          separador decimal dos valores do documento
 * @param debtor                onde estão os dados do devedor
 * @param documentFields        campos lidos uma vez no documento e aplicados a todos os títulos (ex.: data-base do saldo)
 * @param table                 como a tabela de títulos é delimitada e lida
 * @param controlTotals         totais de controle impressos no documento (conferidos com a soma extraída)
 * @param ignoreLinesContaining linhas que contêm um destes textos são ignoradas (rodapé, numeração de página)
 */
public record ExtractionMap(int schemaVersion, String documentType, NumberStyle numberFormat, DebtorSection debtor,
                            List<LabelField> documentFields, TableSection table, List<ControlTotal> controlTotals,
                            List<String> ignoreLinesContaining) {

    public static final int SCHEMA_VERSION = 1;
    public static final String INFO_PREFIX = "info:";
    public static final String PAID_WHEN_FILLED = "pago_se_preenchido";
    public static final String COUNT_TARGET = "quantidade";

    public ExtractionMap {
        numberFormat = numberFormat == null ? NumberStyle.COMMA : numberFormat;
        debtor = debtor == null ? new DebtorSection(DebtorSource.NONE, null, List.of()) : debtor;
        documentFields = documentFields == null ? List.of() : List.copyOf(documentFields);
        controlTotals = controlTotals == null ? List.of() : List.copyOf(controlTotals);
        ignoreLinesContaining = ignoreLinesContaining == null ? List.of() : List.copyOf(ignoreLinesContaining);
    }

    /** Separador decimal dos valores. */
    public enum NumberStyle {
        /** {@code 1.234,56} (pt-BR) */
        COMMA,
        /** {@code 1,234.56} */
        DOT
    }

    /** Onde ficam os dados do devedor. */
    public enum DebtorSource {
        /** bloco de cabeçalho antes da tabela, repetido para cada devedor (rótulo: valor) */
        HEADER,
        /** colunas da própria tabela (uma linha por título com documento e nome) */
        COLUMNS,
        /** o documento não traz o devedor (o mapa fica incompleto) */
        NONE
    }

    /** Posição do valor em relação ao rótulo. */
    public enum ValuePosition { RIGHT, BELOW }

    /** Ajuste textual determinístico do valor lido (sem expressões regulares fornecidas pelo modelo). */
    public enum ValueTransform {
        NONE,
        /** remove o primeiro termo quando é um código numérico ({@code 024502 EMPRESA} → {@code EMPRESA}) */
        DROP_LEADING_CODE,
        /** mantém só o código numérico inicial */
        LEADING_CODE
    }

    /**
     * @param source          origem dos dados do devedor
     * @param blockStartLabel rótulo que abre o bloco de cada devedor (obrigatório em {@code HEADER})
     * @param fields          campos do cabeçalho (em {@code HEADER})
     */
    public record DebtorSection(DebtorSource source, String blockStartLabel, List<LabelField> fields) {

        public DebtorSection {
            source = source == null ? DebtorSource.NONE : source;
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** Campo no formato "Rótulo: valor". */
    public record LabelField(String label, String target, ValuePosition position, ValueTransform transform) {

        public LabelField {
            position = position == null ? ValuePosition.RIGHT : position;
            transform = transform == null ? ValueTransform.NONE : transform;
        }
    }

    /**
     * @param linesPerRecord quantas linhas físicas formam um título (1 a {@value TableSection#MAX_LINES_PER_RECORD})
     * @param headerLines    rótulos das colunas por linha do cabeçalho, na ordem; a linha {@code i} do cabeçalho
     *                       descreve a linha {@code i} de cada registro
     * @param anchorColumn   coluna da 1ª linha sempre preenchida no início de um título (ex.: vencimento)
     * @param endMarkers     textos que encerram a tabela (ex.: "Totais:")
     * @param columns        colunas e seus destinos
     */
    public record TableSection(int linesPerRecord, List<List<String>> headerLines, String anchorColumn,
                               List<String> endMarkers, List<TableColumn> columns) {

        public static final int MAX_LINES_PER_RECORD = 5;

        public TableSection {
            headerLines = headerLines == null ? List.of() : headerLines.stream()
                    .map(l -> l == null ? List.<String>of() : List.copyOf(l)).toList();
            endMarkers = endMarkers == null ? List.of() : List.copyOf(endMarkers);
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        public java.util.Optional<TableColumn> column(String header) {
            return columns.stream().filter(c -> Objects.equals(c.header(), header)).findFirst();
        }
    }

    /**
     * @param header  rótulo do cabeçalho (como em {@code headerLines})
     * @param line    linha do registro (1..linesPerRecord)
     * @param targets destinos (vazio = coluna ignorada); várias colunas com o mesmo destino são unidas com "-" na
     *                ordem do cabeçalho (ex.: empresa + número + parcela → {@code id_titulo})
     * @param xMin    limite esquerdo explícito em pontos (opcional; sem ele, deduzido do cabeçalho)
     * @param xMax    limite direito explícito em pontos (opcional)
     */
    public record TableColumn(String header, int line, List<String> targets, Double xMin, Double xMax) {

        public TableColumn {
            line = line < 1 ? 1 : line;
            targets = targets == null ? List.of() : List.copyOf(targets);
        }

        public boolean ignored() {
            return targets.isEmpty();
        }
    }

    /**
     * Total de controle impresso no documento.
     *
     * @param label  rótulo da linha do total (ex.: "Totais:")
     * @param column coluna da tabela cujo valor está nessa linha (nulo = o valor fica à direita do rótulo)
     * @param target campo canônico somado ({@code saldo}, {@code valor_principal}) ou {@value #COUNT_TARGET}
     * @param scope  bloco do devedor (padrão) ou documento inteiro
     */
    public record ControlTotal(String label, String column, String target, TotalScope scope) {

        public ControlTotal {
            scope = scope == null ? TotalScope.BLOCK : scope;
        }

        public ControlTotal(String label, String column, String target) {
            this(label, column, target, TotalScope.BLOCK);
        }

        public boolean count() {
            return COUNT_TARGET.equals(target);
        }
    }

    /** Abrangência de um total de controle. */
    public enum TotalScope {
        /** total do bloco do devedor em que aparece (ou do documento, se não houver blocos) */
        BLOCK,
        /** total geral do documento */
        DOCUMENT
    }
}
