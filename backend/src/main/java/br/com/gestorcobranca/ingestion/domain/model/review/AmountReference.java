package br.com.gestorcobranca.ingestion.domain.model.review;

/** Referência estatística escolhida para uma linha. */
public record AmountReference(ReferenceKind kind, LogScaleStats stats) {
}
