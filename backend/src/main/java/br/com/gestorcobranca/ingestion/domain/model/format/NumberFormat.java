package br.com.gestorcobranca.ingestion.domain.model.format;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interpretação aplicada às colunas monetárias de um lote. Colunas sem decisão registrada (lotes anteriores a esta
 * regra) são lidas valor a valor, com os ambíguos como milhar — o comportamento histórico.
 *
 * @param columns decisão por nome canônico de coluna
 */
public record NumberFormat(Map<String, ColumnDecision> columns) {

    /** Padrão histórico (lotes sem formato registrado). */
    public static final FormatOption LEGACY = FormatOption.PER_VALUE_GROUPING;

    public NumberFormat {
        columns = Map.copyOf(columns);
    }

    public static NumberFormat empty() {
        return new NumberFormat(Map.of());
    }

    public FormatOption option(CanonicalField field) {
        ColumnDecision decision = columns.get(field.column());
        return decision == null ? LEGACY : decision.option();
    }

    public Optional<ColumnDecision> decision(String column) {
        return Optional.ofNullable(columns.get(column));
    }

    public NumberFormat with(String column, ColumnDecision decision) {
        Map<String, ColumnDecision> copy = new LinkedHashMap<>(columns);
        copy.put(column, decision);
        return new NumberFormat(copy);
    }

    /**
     * Combina a análise do arquivo com decisões já informadas (usuário ou integração).
     *
     * @param preset decisões informadas; as de colunas sem necessidade de decisão são ignoradas
     */
    public static Resolution resolve(FileFormatAnalysis analysis, NumberFormat preset) {
        Map<String, ColumnDecision> applied = new LinkedHashMap<>();
        List<String> undecided = new ArrayList<>();
        List<String> conflicting = new ArrayList<>();
        for (ColumnFormatAnalysis column : analysis.columns()) {
            Optional<ColumnDecision> given = preset == null ? Optional.empty() : preset.decision(column.column());
            if (column.status() == ColumnFormatStatus.NOT_NEEDED) {
                applied.put(column.column(), column.autoDecision().orElseThrow());
            } else if (given.isPresent()) {
                if (column.allowed().contains(given.get().option())) {
                    applied.put(column.column(), given.get());
                } else {
                    conflicting.add(column.column());
                }
            } else if (column.requiresDecision()) {
                undecided.add(column.column());
            } else {
                applied.put(column.column(), column.autoDecision().orElseThrow());
            }
        }
        return new Resolution(new NumberFormat(applied), undecided, conflicting);
    }

    /**
     * @param undecided   colunas ambíguas ou misturadas sem decisão
     * @param conflicting colunas cuja decisão informada não é compatível com os valores do arquivo
     */
    public record Resolution(NumberFormat applied, List<String> undecided, List<String> conflicting) {

        public Resolution {
            undecided = List.copyOf(undecided);
            conflicting = List.copyOf(conflicting);
        }

        public boolean complete() {
            return undecided.isEmpty() && conflicting.isEmpty();
        }
    }
}
