package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnDecision;
import br.com.gestorcobranca.ingestion.domain.model.format.DecisionSource;
import br.com.gestorcobranca.ingestion.domain.model.format.FileFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import br.com.gestorcobranca.ingestion.domain.model.format.MonetaryFields;
import br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conversão das decisões de formato entre a API, o domínio e o JSON gravado no lote. */
final class NumberFormats {

    private NumberFormats() {
    }

    /** A mesma convenção para todas as colunas monetárias ({@code decimalSeparator} do envio de arquivo). */
    static NumberFormat forAllColumns(String decimalSeparator, DecisionSource source) {
        if (decimalSeparator == null || decimalSeparator.isBlank()) {
            return null;
        }
        FormatOption option = FormatOption.parse(decimalSeparator).filter(o -> !o.perValue())
                .orElseThrow(() -> new BusinessRuleException("import.decimal_separator_invalid",
                        "Separador decimal deve ser COMMA (vírgula) ou DOT (ponto)"));
        Map<String, ColumnDecision> columns = new LinkedHashMap<>();
        for (CanonicalField field : MonetaryFields.ALL) {
            columns.put(field.column(), new ColumnDecision(option, source));
        }
        return new NumberFormat(columns);
    }

    /** Decisões por coluna ({@code valor_principal → COMMA}); colunas e opções são validadas. */
    static NumberFormat fromColumns(Map<String, String> decisions, DecisionSource source) {
        if (decisions == null || decisions.isEmpty()) {
            return null;
        }
        Map<String, ColumnDecision> columns = new LinkedHashMap<>();
        decisions.forEach((column, raw) -> {
            CanonicalField field = MonetaryFields.byColumn(column).orElseThrow(() -> new BusinessRuleException(
                    "import.number_format_column_invalid", "Coluna monetária desconhecida: " + column));
            FormatOption option = FormatOption.parse(raw).orElseThrow(() -> new BusinessRuleException(
                    "import.number_format_option_invalid",
                    "Interpretação inválida para %s: use COMMA, DOT, PER_VALUE_GROUPING ou PER_VALUE_DECIMAL".formatted(column)));
            columns.put(field.column(), new ColumnDecision(option, source));
        });
        return new NumberFormat(columns);
    }

    static String toJson(JsonCodec json, NumberFormat format) {
        return format == null || format.columns().isEmpty() ? null : json.toJson(format);
    }

    static NumberFormat fromJson(JsonCodec json, String value) {
        return value == null || value.isBlank() ? null : json.fromJson(value, NumberFormat.class);
    }

    static FileFormatAnalysis analysisFromJson(JsonCodec json, String value) {
        return value == null || value.isBlank() ? null : json.fromJson(value, FileFormatAnalysis.class);
    }

    /** Coluna → opção, para as views. */
    static Map<String, String> summary(NumberFormat format) {
        Map<String, String> map = new LinkedHashMap<>();
        if (format != null) {
            format.columns().forEach((column, decision) -> map.put(column, decision.option().name()));
        }
        return map;
    }
}
