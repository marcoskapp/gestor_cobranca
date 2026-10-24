package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertFinding;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.LogScaleStats;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalInt;

/** Sinais de valor além do ticket médio ({@code importacao.md} §4.3). Funções puras. */
public final class ReviewSignals {

    private static final String PRINCIPAL = CanonicalField.PRINCIPAL_AMOUNT.column();
    private static final String BALANCE = CanonicalField.REPORTED_BALANCE.column();
    private static final String DUE = CanonicalField.DUE_DATE.column();
    private static final String CONTRACT = CanonicalField.CONTRACT_AMOUNT.column();

    private final AlertSettings settings;

    public ReviewSignals(AlertSettings settings) {
        this.settings = settings;
    }

    /**
     * Saldo informado acima do que multa e encargos justificariam: {@code principal × (1 + multa + encargo mensal ×
     * meses em atraso)}, com no mínimo um mês.
     */
    public Optional<AlertFinding> balance(long row, BigDecimal principal, BigDecimal balance, LocalDate dueDate,
                                          LocalDate today) {
        if (principal == null || balance == null || principal.signum() <= 0 || balance.signum() <= 0) {
            return Optional.empty();
        }
        long months = dueDate == null || !dueDate.isBefore(today) ? 1 : Math.max(1, ChronoUnit.MONTHS.between(dueDate, today));
        BigDecimal factor = BigDecimal.valueOf(1 + settings.balanceFineRate() + settings.balanceMonthlyRate() * months);
        BigDecimal ceiling = principal.multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
        if (balance.compareTo(ceiling) <= 0) {
            return Optional.empty();
        }
        OptionalInt n = ScaleRatio.exponent(balance, principal, 0.05);
        String detail = "Saldo %s maior que o principal %s além do que multa e encargos de %d mês(es) justificariam (até %s)."
                .formatted(Brl.format(balance), Brl.format(principal), months, Brl.format(ceiling));
        if (n.isPresent()) {
            detail += " A razão é de %s vezes: confira o separador decimal do saldo.".formatted(pow(n.getAsInt()));
        }
        int priority = AlertType.BALANCE_ABOVE_PRINCIPAL.priority() + (n.isPresent() ? 20 : 0);
        return Optional.of(new AlertFinding(row, AlertType.BALANCE_ABOVE_PRINCIPAL, priority, BALANCE, balance, null, ceiling,
                null, null, n.isPresent() ? -n.getAsInt() : null, detail));
    }

    /** Vencimento distante demais no futuro ou antigo demais. */
    public Optional<AlertFinding> dueDate(long row, LocalDate dueDate, LocalDate today) {
        if (dueDate == null) {
            return Optional.empty();
        }
        if (dueDate.isAfter(today.plusYears(settings.maxFutureYears()))) {
            return Optional.of(new AlertFinding(row, AlertType.DUE_DATE_TOO_FUTURE, AlertType.DUE_DATE_TOO_FUTURE.priority(),
                    DUE, null, null, null, null, null, null,
                    "Vencimento em %s, mais de %d anos à frente: confira o ano.".formatted(br(dueDate), settings.maxFutureYears())));
        }
        if (dueDate.isBefore(today.minusYears(settings.maxPastYears()))) {
            return Optional.of(new AlertFinding(row, AlertType.DUE_DATE_TOO_OLD, AlertType.DUE_DATE_TOO_OLD.priority(),
                    DUE, null, null, null, null, null, null,
                    "Vencimento em %s, há mais de %d anos: confira o ano e a prescrição.".formatted(br(dueDate),
                            settings.maxPastYears())));
        }
        return Optional.empty();
    }

    /** Título do mesmo devedor com magnitude muito diferente da mediana dos títulos dele. */
    public Optional<AlertFinding> debtorSpread(long row, BigDecimal amount, BigDecimal debtorMedian) {
        if (amount == null || debtorMedian == null || amount.signum() <= 0 || debtorMedian.signum() <= 0) {
            return Optional.empty();
        }
        double ratio = amount.divide(debtorMedian, MathContext.DECIMAL64).doubleValue();
        if (ratio < settings.debtorSpreadRatio() && ratio > 1 / settings.debtorSpreadRatio()) {
            return Optional.empty();
        }
        OptionalInt n = ScaleRatio.exponent(ratio, 0.05);
        String detail = "Valor %s é %s a mediana dos outros títulos do mesmo devedor (%s).".formatted(Brl.format(amount),
                ratio >= 1 ? "%.0f vezes".formatted(ratio) : "1/%.0f de".formatted(1 / ratio), Brl.format(debtorMedian));
        if (n.isPresent()) {
            detail += " Razão de potência de 10: confira o separador decimal.";
        }
        return Optional.of(new AlertFinding(row, AlertType.DEBTOR_MAGNITUDE_SPREAD,
                AlertType.DEBTOR_MAGNITUDE_SPREAD.priority() + (n.isPresent() ? 20 : 0), PRINCIPAL, amount, null, null, null,
                null, n.isPresent() ? -n.getAsInt() : null, detail));
    }

    /**
     * Soma das parcelas de um contrato muito acima do valor do contrato (ou em razão 10ⁿ). Soma menor é comum (só as
     * parcelas em atraso foram enviadas) e não gera alerta.
     */
    public Optional<AlertFinding> contract(long row, String contract, long lines, BigDecimal installmentsSum,
                                           BigDecimal contractAmount) {
        if (lines < 2 || installmentsSum == null || contractAmount == null || contractAmount.signum() <= 0
                || installmentsSum.signum() <= 0) {
            return Optional.empty();
        }
        double ratio = installmentsSum.divide(contractAmount, MathContext.DECIMAL64).doubleValue();
        OptionalInt n = ScaleRatio.exponent(ratio, 0.05);
        if (ratio <= settings.contractOverRatio() && n.isEmpty()) {
            return Optional.empty();
        }
        String detail = "Soma das %d parcelas do contrato %s (%s) não confere com o valor do contrato (%s)%s.".formatted(lines,
                contract, Brl.format(installmentsSum), Brl.format(contractAmount),
                n.isPresent() ? ": razão de %s vezes, confira o separador decimal".formatted(pow(n.getAsInt())) : "");
        return Optional.of(new AlertFinding(row, AlertType.CONTRACT_INSTALLMENTS_MISMATCH,
                AlertType.CONTRACT_INSTALLMENTS_MISMATCH.priority() + (n.isPresent() ? 20 : 0), CONTRACT, contractAmount,
                null, null, null, null, n.isPresent() ? n.getAsInt() : null, detail));
    }

    /**
     * Centavos sempre zerados no arquivo quando o histórico tem centavos (separador perdido ou valores em centavos), ou
     * o contrário. Linha 0 (arquivo).
     */
    public Optional<AlertFinding> cents(double fileIntegerShare, long fileSize, double historyIntegerShare, long historySize) {
        if (fileSize < settings.minSample() || historySize < settings.minSample()) {
            return Optional.empty();
        }
        String detail;
        if (fileIntegerShare >= settings.fileIntegerShare() && historyIntegerShare <= settings.historyIntegerShare()) {
            detail = "%s dos valores principais do arquivo estão sem centavos, mas só %s no histórico do credor: confira se o separador decimal foi perdido (1.234,56 lido como 123456) ou se os valores estão em centavos."
                    .formatted(percent(fileIntegerShare), percent(historyIntegerShare));
        } else if (historyIntegerShare >= settings.fileIntegerShare() && fileIntegerShare <= settings.historyIntegerShare()) {
            detail = "O histórico do credor não tem centavos (%s sem centavos), mas este arquivo tem centavos em %s dos valores: confira a unidade dos valores."
                    .formatted(percent(historyIntegerShare), percent(1 - fileIntegerShare));
        } else {
            return Optional.empty();
        }
        return Optional.of(new AlertFinding(0, AlertType.CENTS_PATTERN, AlertType.CENTS_PATTERN.priority(), PRINCIPAL, null,
                null, null, null, historySize, null, detail));
    }

    /** Mediana do arquivo em razão 10ⁿ com a do histórico (o arquivo inteiro deslocado de escala). Linha 0. */
    public Optional<AlertFinding> fileShift(LogScaleStats file, LogScaleStats history) {
        if (file == null || history == null || file.size() < settings.minSample() || history.size() < settings.minSample()) {
            return Optional.empty();
        }
        OptionalInt n = ScaleRatio.exponent(file.median() / history.median(), settings.fileShiftTolerance());
        if (n.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal fileMedian = BigDecimal.valueOf(file.median()).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal historyMedian = BigDecimal.valueOf(history.median()).setScale(2, RoundingMode.HALF_EVEN);
        String detail = "O valor típico deste arquivo (%s) é cerca de %s vezes o do histórico do credor (%s): o arquivo inteiro pode estar com o separador decimal trocado."
                .formatted(Brl.format(fileMedian), pow(n.getAsInt()), Brl.format(historyMedian));
        return Optional.of(new AlertFinding(0, AlertType.FILE_SCALE_SHIFT, AlertType.FILE_SCALE_SHIFT.priority(), PRINCIPAL,
                fileMedian, null, historyMedian, null, history.size(), -n.getAsInt(), detail));
    }

    private static String pow(int n) {
        String digits = "1" + "0".repeat(Math.abs(n));
        return n > 0 ? digits : "1/" + digits;
    }

    private static String percent(double share) {
        return "%d%%".formatted(Math.round(share * 100));
    }

    private static String br(LocalDate date) {
        return "%02d/%02d/%04d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }
}
