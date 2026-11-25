package br.com.gestorcobranca.ingestion.domain.model.review;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Valores de uma linha da prévia, sem dado pessoal, para os totais por dimensão e a análise de valores
 * ({@code ingestion.import_row_value}). Gravado para <b>todas</b> as linhas de dados, inclusive as com erro: a soma da
 * planilha do usuário também as inclui.
 *
 * @param valid             a linha passou na validação
 * @param principalInvalid  havia valor principal, mas ele não pôde ser lido
 * @param principalAmbiguous o texto do principal era ambíguo ({@code 1.000}) e foi resolvido pela interpretação da coluna
 * @param situation         situação informada já normalizada (ATIVO…), ou o texto original truncado se inválida
 * @param debtorKey         chave do devedor (blind index do documento) — nunca o documento
 */
public record RowValue(long rowNumber, boolean valid, BigDecimal principal, boolean principalInvalid,
                       boolean principalAmbiguous, BigDecimal balance, boolean balanceInvalid, BigDecimal contractAmount,
                       boolean contractInvalid, LocalDate dueDate, String situation, String product, String contractNumber,
                       String externalId, String debtorKey) {

    /**
     * Só os valores dos totais, sem identificadores nem índice do documento: linha de titular eliminado (D01h, D01k;
     * {@code import_row_value} não pode ser alterada depois de gravada).
     */
    public RowValue withoutIdentifiers() {
        return new RowValue(rowNumber, valid, principal, principalInvalid, principalAmbiguous, balance, balanceInvalid,
                contractAmount, contractInvalid, dueDate, situation, product, null, null, null);
    }
}
