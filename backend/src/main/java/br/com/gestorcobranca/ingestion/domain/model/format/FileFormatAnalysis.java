package br.com.gestorcobranca.ingestion.domain.model.format;

import java.util.List;
import java.util.Optional;

/**
 * Análise do formato numérico do arquivo inteiro: uma entrada por coluna monetária presente.
 *
 * @param rows linhas de dados lidas na leitura prévia
 */
public record FileFormatAnalysis(long rows, List<ColumnFormatAnalysis> columns) {

    public FileFormatAnalysis {
        columns = List.copyOf(columns);
    }

    public Optional<ColumnFormatAnalysis> column(String column) {
        return columns.stream().filter(c -> c.column().equals(column)).findFirst();
    }

    public boolean requiresDecision() {
        return columns.stream().anyMatch(ColumnFormatAnalysis::requiresDecision);
    }

    /** Valores em texto em colunas monetárias (no XLSX, "números armazenados como texto"). */
    public long textValues() {
        return columns.stream().mapToLong(ColumnFormatAnalysis::textCount).sum();
    }

    /**
     * Sugestão para uma coluna que exige decisão: a convenção provada pelas demais colunas do arquivo, se todas
     * concordarem. É só exibida — nunca aplicada sem o usuário.
     */
    public Optional<FormatOption> suggestion(String column) {
        List<FormatOption> others = columns.stream()
                .filter(c -> !c.column().equals(column))
                .filter(c -> c.status() == ColumnFormatStatus.DETECTED || c.status() == ColumnFormatStatus.INFERRED)
                .map(ColumnFormatAnalysis::recommended)
                .distinct()
                .toList();
        return others.size() == 1 ? Optional.of(others.getFirst()) : Optional.empty();
    }
}
