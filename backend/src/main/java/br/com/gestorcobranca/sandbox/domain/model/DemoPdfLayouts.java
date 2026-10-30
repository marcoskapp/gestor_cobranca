package br.com.gestorcobranca.sandbox.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Os dois layouts dos PDFs fictícios de demonstração e os respectivos mapas de extração (objeto JSON do esquema
 * {@code ingestion/pdf/extraction-map.schema.json}, o mesmo que a tela edita e que a IA proporia). Os rótulos ficam
 * aqui, num lugar só, para o desenho do PDF e o mapa nunca divergirem.
 * <p>
 * O mapa do histórico é salvo pela carga como mapa do 1º credor de cada assessoria. O da tabela simples
 * <strong>não</strong> é salvo: é o gabarito do roteiro de demonstração (montagem manual do mapa na tela) e dos testes.
 */
public final class DemoPdfLayouts {

    /** Nome do mapa salvo pela carga no 1º credor (idempotência da carga). */
    public static final String HISTORY_TEMPLATE_NAME = "Histórico financeiro por cliente (demonstração)";

    public static final String HISTORY_HEADING = "Histórico Financeiro por Cliente";
    /** 1ª linha do cabeçalho da tabela do histórico, na ordem. */
    public static final List<String> HISTORY_LINE_1 = List.of("Emp", "Número", "Lt", "Emissão", "Vencto", "Pagto",
            "Atraso", "Valor", "Pendente", "Jur Mora", "Forma Pgto", "Nosso Número");
    /** 2ª linha do cabeçalho (2ª linha de cada título). */
    public static final String HISTORY_LINE_2 = "Conta";
    public static final String HISTORY_TOTALS = "Totais:";

    public static final String TABLE_HEADING = "Carteira para cobrança";
    public static final List<String> TABLE_HEADER = List.of("Documento", "Nome", "Contrato", "Parcela", "Vencimento",
            "Valor");
    public static final String TABLE_POSITION = "Posição em:";
    public static final String TABLE_TOTAL = "Total:";
    public static final String TABLE_COUNT = "Quantidade:";

    /** Rodapé de página dos dois layouts (linhas ignoradas pelo mapa). */
    public static final String PAGE_FOOTER = "Página";

    private DemoPdfLayouts() {
    }

    /** Mapa do "Histórico Financeiro por Cliente": devedor no cabeçalho, título em duas linhas, totais do bloco. */
    public static Map<String, Object> historyMap() {
        Map<String, Object> debtor = new LinkedHashMap<>();
        debtor.put("source", "HEADER");
        debtor.put("blockStartLabel", "Cliente:");
        debtor.put("fields", List.of(
                field("Cliente:", "nome", "DROP_LEADING_CODE"),
                field("Cliente:", "info:codigo_cliente", "LEADING_CODE"),
                field("CPF/CNPJ:", "documento", "NONE"),
                field("Endereço:", "endereco_logradouro", "NONE"),
                field("Bairro:", "endereco_bairro", "NONE"),
                field("Telefone:", "telefone_1", "NONE"),
                field("Cidade:", "endereco_cidade", "NONE"),
                field("Email:", "email_1", "NONE")));
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("linesPerRecord", 2);
        table.put("headerLines", List.of(HISTORY_LINE_1, List.of(HISTORY_LINE_2)));
        table.put("anchorColumn", "Vencto");
        table.put("endMarkers", List.of(HISTORY_TOTALS));
        table.put("columns", List.of(
                column("Emp", 1, "id_titulo"),
                column("Número", 1, "id_titulo", "contrato"),
                column("Lt", 1, "id_titulo"),
                column("Emissão", 1, "info:emissao"),
                column("Vencto", 1, "vencimento"),
                column("Pagto", 1, "pago_se_preenchido"),
                column("Atraso", 1, "info:dias_atraso"),
                column("Valor", 1, "valor_principal"),
                column("Pendente", 1, "saldo"),
                column("Jur Mora", 1, "info:juros_mora"),
                column("Forma Pgto", 1, "info:forma_pagamento"),
                column("Nosso Número", 1, "info:nosso_numero"),
                column(HISTORY_LINE_2, 2, "info:conta")));
        return map("Histórico financeiro por cliente", debtor, List.of(field("Data Emissão:", "data_saldo", "NONE")),
                table, List.of(control(HISTORY_TOTALS, "Valor", "valor_principal", "BLOCK"),
                        control(HISTORY_TOTALS, "Pendente", "saldo", "BLOCK")));
    }

    /** Gabarito do mapa da tabela simples (montado à mão na tela durante a demonstração). */
    public static Map<String, Object> tableMap() {
        Map<String, Object> debtor = new LinkedHashMap<>();
        debtor.put("source", "COLUMNS");
        debtor.put("fields", List.of());
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("linesPerRecord", 1);
        table.put("headerLines", List.of(TABLE_HEADER));
        table.put("anchorColumn", "Vencimento");
        table.put("endMarkers", List.of(TABLE_TOTAL));
        table.put("columns", List.of(
                column("Documento", 1, "documento"),
                column("Nome", 1, "nome"),
                column("Contrato", 1, "contrato", "id_titulo"),
                column("Parcela", 1, "parcela", "id_titulo"),
                column("Vencimento", 1, "vencimento"),
                column("Valor", 1, "valor_principal")));
        return map("Carteira para cobrança", debtor, List.of(field(TABLE_POSITION, "data_saldo", "NONE")), table,
                List.of(control(TABLE_TOTAL, null, "valor_principal", "DOCUMENT"),
                        control(TABLE_COUNT, null, "quantidade", "DOCUMENT")));
    }

    private static Map<String, Object> map(String documentType, Map<String, Object> debtor,
                                           List<Map<String, Object>> documentFields, Map<String, Object> table,
                                           List<Map<String, Object>> controls) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", 1);
        map.put("documentType", documentType);
        map.put("numberFormat", "COMMA");
        map.put("debtor", debtor);
        map.put("documentFields", documentFields);
        map.put("table", table);
        map.put("controlTotals", controls);
        map.put("ignoreLinesContaining", List.of(PAGE_FOOTER));
        return map;
    }

    private static Map<String, Object> field(String label, String target, String transform) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("target", target);
        f.put("position", "RIGHT");
        f.put("transform", transform);
        return f;
    }

    private static Map<String, Object> column(String header, int line, String... targets) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("header", header);
        c.put("line", line);
        c.put("targets", new ArrayList<>(List.of(targets)));
        return c;
    }

    private static Map<String, Object> control(String label, String column, String target, String scope) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("label", label);
        if (column != null) {
            c.put("column", column);
        }
        c.put("target", target);
        c.put("scope", scope);
        return c;
    }
}
