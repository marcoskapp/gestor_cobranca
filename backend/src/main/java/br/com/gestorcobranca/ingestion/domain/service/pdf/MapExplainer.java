package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSource;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.LabelField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableColumn;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TotalScope;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ValuePosition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O mapa em linguagem simples, para a tela de validação ("Coluna 'Pendente' → saldo devedor do título"; "cada
 * título ocupa 2 linhas"; "devedor lido no cabeçalho").
 */
public final class MapExplainer {

    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("documento", "CPF/CNPJ do devedor"), Map.entry("nome", "nome do devedor"),
            Map.entry("tipo_pessoa", "tipo de pessoa"), Map.entry("data_nascimento", "data de nascimento"),
            Map.entry("telefone_1", "telefone"), Map.entry("telefone_2", "telefone 2"), Map.entry("telefone_3", "telefone 3"),
            Map.entry("email_1", "e-mail"), Map.entry("email_2", "e-mail 2"),
            Map.entry("endereco_logradouro", "endereço"), Map.entry("endereco_numero", "número do endereço"),
            Map.entry("endereco_complemento", "complemento"), Map.entry("endereco_bairro", "bairro"),
            Map.entry("endereco_cidade", "cidade"), Map.entry("endereco_uf", "UF"), Map.entry("endereco_cep", "CEP"),
            Map.entry("contrato", "número do contrato"), Map.entry("produto", "produto"),
            Map.entry("data_contrato", "data do contrato"), Map.entry("valor_contrato", "valor do contrato"),
            Map.entry("id_titulo", "identificador do título (chave da reimportação)"),
            Map.entry("parcela", "número da parcela"), Map.entry("vencimento", "vencimento do título"),
            Map.entry("valor_principal", "valor original (principal) do título"),
            Map.entry("saldo", "saldo devedor do título"), Map.entry("data_saldo", "data-base do saldo"),
            Map.entry("situacao", "situação do título no credor"));

    private MapExplainer() {
    }

    public static String describe(String target) {
        if (target == null) {
            return "—";
        }
        if (target.startsWith(ExtractionMap.INFO_PREFIX)) {
            return "informação adicional \"" + target.substring(ExtractionMap.INFO_PREFIX.length()).replace('_', ' ')
                    + "\" (guardada, não vai ao lote)";
        }
        if (ExtractionMap.PAID_WHEN_FILLED.equals(target)) {
            return "data de pagamento: preenchida, o título vai como PAGO";
        }
        if (ExtractionMap.COUNT_TARGET.equals(target)) {
            return "quantidade de títulos";
        }
        String label = FIELDS.get(target);
        return label == null ? target : label + " (" + target + ")";
    }

    public static List<String> explain(ExtractionMap map) {
        List<String> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        out.add("Documento: " + (map.documentType() == null || map.documentType().isBlank() ? "não descrito"
                : map.documentType()) + "; valores com " + (map.numberFormat() == ExtractionMap.NumberStyle.DOT
                ? "ponto decimal (1,234.56)" : "vírgula decimal (1.234,56)") + ".");
        if (map.debtor().source() == DebtorSource.HEADER) {
            out.add("Devedor lido no cabeçalho de cada bloco, que começa em \"" + map.debtor().blockStartLabel() + "\".");
            for (LabelField f : map.debtor().fields()) {
                out.add(field(f));
            }
        } else if (map.debtor().source() == DebtorSource.COLUMNS) {
            out.add("Devedor lido nas colunas da própria tabela (um título por linha com documento e nome).");
        } else {
            out.add("O documento não indica o devedor: o mapa está incompleto.");
        }
        for (LabelField f : map.documentFields()) {
            out.add(field(f) + " Vale para todos os títulos.");
        }
        if (map.table() != null) {
            int lines = map.table().linesPerRecord();
            out.add(lines == 1 ? "Cada título ocupa 1 linha da tabela."
                    : "Cada título ocupa " + lines + " linhas; um título começa quando a coluna \""
                    + map.table().anchorColumn() + "\" está preenchida.");
            if (!map.table().endMarkers().isEmpty()) {
                out.add("A tabela termina na linha com \"" + String.join("\" ou \"", map.table().endMarkers()) + "\".");
            }
            Map<String, List<String>> joined = new LinkedHashMap<>();
            for (TableColumn c : CanonicalAssembler.orderedColumns(map)) {
                if (c.ignored()) {
                    out.add("Coluna \"" + c.header() + "\" ignorada.");
                    continue;
                }
                for (String t : c.targets()) {
                    joined.computeIfAbsent(t, k -> new ArrayList<>()).add(c.header());
                }
            }
            joined.forEach((target, headers) -> out.add((headers.size() == 1 ? "Coluna \"" + headers.getFirst() + "\""
                    : "Colunas \"" + String.join("\" + \"", headers) + "\" (unidas com \"" + CanonicalAssembler.JOIN + "\")")
                    + " → " + describe(target) + "."));
        }
        for (ControlTotal c : map.controlTotals()) {
            out.add("Total de controle \"" + c.label() + "\"" + (c.column() == null ? "" : " na coluna \"" + c.column() + "\"")
                    + " conferido com a " + (c.count() ? "quantidade de títulos" : "soma de " + describe(c.target()))
                    + (c.scope() == TotalScope.DOCUMENT ? " do documento inteiro." : " do bloco do devedor."));
        }
        if (!map.ignoreLinesContaining().isEmpty()) {
            out.add("Linhas com \"" + String.join("\", \"", map.ignoreLinesContaining()) + "\" são ignoradas.");
        }
        return out;
    }

    private static String field(LabelField f) {
        String where = f.position() == ValuePosition.BELOW ? "abaixo de" : "à direita de";
        String transform = switch (f.transform()) {
            case DROP_LEADING_CODE -> " (sem o código inicial)";
            case LEADING_CODE -> " (só o código inicial)";
            case NONE -> "";
        };
        return "\"" + f.label() + "\": o valor " + where + " o rótulo" + transform + " → " + describe(f.target()) + ".";
    }
}
