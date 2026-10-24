package br.com.gestorcobranca.ingestion.domain.model.review;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;

/** Total de controle que o usuário pode conferir com a planilha. */
public enum TotalsMeasure {
    /** Quantidade de linhas de dados (sem o cabeçalho). */
    ROWS(null),
    PRINCIPAL(CanonicalField.PRINCIPAL_AMOUNT),
    BALANCE(CanonicalField.REPORTED_BALANCE),
    /** Soma da coluna {@code valor_contrato} como está na planilha (com as repetições por parcela). */
    CONTRACT_AMOUNT(CanonicalField.CONTRACT_AMOUNT);

    private final CanonicalField field;

    TotalsMeasure(CanonicalField field) {
        this.field = field;
    }

    /** Coluna somada (nula para a quantidade de linhas). */
    public CanonicalField field() {
        return field;
    }
}
