package br.com.gestorcobranca.ingestion.domain.model.format;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;

import java.util.List;
import java.util.Optional;

/** Colunas monetárias do layout canônico, cuja convenção numérica é analisada por coluna. */
public final class MonetaryFields {

    public static final List<CanonicalField> ALL = List.of(CanonicalField.PRINCIPAL_AMOUNT,
            CanonicalField.REPORTED_BALANCE, CanonicalField.CONTRACT_AMOUNT, CanonicalField.APPRAISAL_AMOUNT);

    private MonetaryFields() {
    }

    public static Optional<CanonicalField> byColumn(String column) {
        return CanonicalField.byColumn(column).filter(ALL::contains);
    }
}
