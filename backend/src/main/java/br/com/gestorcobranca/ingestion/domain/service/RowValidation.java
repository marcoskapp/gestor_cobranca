package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;

import java.util.List;
import java.util.Optional;

/**
 * Resultado da validação de uma linha: a linha convertida (somente se não houver erro) e as
 * ocorrências (erros e avisos).
 */
public record RowValidation(long rowNumber, ParsedRow row, List<RowIssue> issues) {

    public RowValidation {
        issues = List.copyOf(issues);
    }

    public Optional<ParsedRow> valid() {
        return Optional.ofNullable(row);
    }

    public List<RowIssue> errors() {
        return issues.stream().filter(RowIssue::isError).toList();
    }

    public List<RowIssue> warnings() {
        return issues.stream().filter(i -> !i.isError()).toList();
    }
}
