package br.com.gestorcobranca.ingestion.domain.model.format;

/** Interpretação aplicada a uma coluna monetária e de onde ela veio. */
public record ColumnDecision(FormatOption option, DecisionSource source) {
}
