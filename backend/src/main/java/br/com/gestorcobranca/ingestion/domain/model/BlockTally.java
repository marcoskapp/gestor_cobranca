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
    private Money principalTotal = Money.ZERO;

    /** Linha válida; {@code titleOutcome} nulo na pré-visualização (nada é gravado). */
    public void validRow(BigDecimal principal, int warningCount, RowOutcome titleOutcome) {
        rowsRead++;
        rowsValid++;
        warnings += warningCount;
        principalTotal = principalTotal.plus(Money.of(principal));
        if (titleOutcome != null) {
            switch (titleOutcome) {
                case CREATED -> titlesCreated++;
                case UPDATED -> titlesUpdated++;
                case UNCHANGED -> titlesUnchanged++;
            }
        }
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

    public Money principalTotal() {
        return principalTotal;
    }
}
