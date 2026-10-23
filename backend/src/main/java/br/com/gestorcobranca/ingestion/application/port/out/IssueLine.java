package br.com.gestorcobranca.ingestion.application.port.out;

/** Linha do relatório de ocorrências (trecho de valor já mascarado). */
public record IssueLine(long rowNumber, String severity, String field, String code, String message,
                        String valueExcerpt) {
}
