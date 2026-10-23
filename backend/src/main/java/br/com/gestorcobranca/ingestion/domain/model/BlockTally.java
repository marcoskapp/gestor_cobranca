package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;

/** Contadores acumulados durante o processamento de um bloco de linhas. */
public final class BlockTally {

    private int rowsRead;
    private int rowsValid;
    private int rowsWithError;
    private int errors;
    private int warnings;
    private int titlesCreated;
    private int titlesUpdated;
    private int titlesUnchanged;
    private int titlesSkipped;
    private int titlesSuspended;
    private Money principalTotal = Money.ZERO;

    /**
     * Linha válida. {@code titleOutcome} nulo na pré-visualização (nada é gravado); título não
     * gravado por decisão da carga ({@link RowOutcome#SKIPPED}, ex.: informado como pago) não soma
     * ao principal carregado.
     *
     * @param suspended a carga suspendeu o título a pedido do credor
     */
    public void validRow(BigDecimal principal, int warningCount, RowOutcome titleOutcome, boolean suspended) {
        rowsRead++;
        rowsValid++;
        warnings += warningCount;
        if (titleOutcome != RowOutcome.SKIPPED) {
            principalTotal = principalTotal.plus(Money.of(principal));
        }
        if (titleOutcome != null) {
            switch (titleOutcome) {
                case CREATED -> titlesCreated++;
                case UPDATED -> titlesUpdated++;
                case UNCHANGED -> titlesUnchanged++;
                case SKIPPED -> titlesSkipped++;
            }
        }
        if (suspended) {
            titlesSuspended++;
        }
    }

    /** Avisos do arquivo como um todo (linha 0), como colunas ignoradas. */
    public void fileWarnings(int count) {
        warnings += count;
    }

    public void rejectedRow(int errorCount, int warningCount) {
        rowsRead++;
        rowsWithError++;
        errors += errorCount;
        warnings += warningCount;
    }

    public int rowsRead() {
        return rowsRead;
    }

    public int rowsValid() {
        return rowsValid;
    }

    public int rowsWithError() {
        return rowsWithError;
    }

    public int errors() {
        return errors;
    }

    public int warnings() {
        return warnings;
    }

    public int titlesCreated() {
        return titlesCreated;
    }

    public int titlesUpdated() {
        return titlesUpdated;
    }

    public int titlesUnchanged() {
        return titlesUnchanged;
    }

    public int titlesSkipped() {
        return titlesSkipped;
    }

    public int titlesSuspended() {
        return titlesSuspended;
    }

    public Money principalTotal() {
        return principalTotal;
    }
}
