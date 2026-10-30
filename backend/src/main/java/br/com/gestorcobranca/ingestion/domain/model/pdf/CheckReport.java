package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.List;

/** Verificações automáticas de uma extração, antes da tela de validação. */
public record CheckReport(List<Finding> findings, List<TotalsCheck> totals) {

    public CheckReport {
        findings = List.copyOf(findings);
        totals = List.copyOf(totals);
    }

    public boolean blocking() {
        return findings.stream().anyMatch(Finding::blocking);
    }

    public boolean totalsMatch() {
        return totals.stream().allMatch(TotalsCheck::matches);
    }

    public long count(Finding.Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }
}
