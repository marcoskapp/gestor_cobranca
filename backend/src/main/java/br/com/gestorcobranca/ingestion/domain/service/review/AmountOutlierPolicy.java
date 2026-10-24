package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.review.AlertFinding;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.AmountReference;
import br.com.gestorcobranca.ingestion.domain.model.review.LogScaleStats;
import br.com.gestorcobranca.ingestion.domain.model.review.ReferenceKind;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Valor atípico em relação ao "ticket médio" ({@code importacao.md} §4.1): escore robusto em escala logarítmica e
 * teste de escala — se o valor dividido (ou multiplicado) por 10, 100 ou 1000 cai na faixa típica, o alerta é de
 * provável separador decimal. Função pura.
 */
public final class AmountOutlierPolicy {

    private final AlertSettings settings;

    public AmountOutlierPolicy(AlertSettings settings) {
        this.settings = settings;
    }

    public Optional<AlertFinding> evaluate(long rowNumber, String field, BigDecimal amount, AmountReference reference) {
        if (reference == null || amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }
        LogScaleStats stats = reference.stats();
        double floor = settings.sigmaFloor();
        double value = amount.doubleValue();
        double z = stats.z(value, floor);
        if (Math.abs(z) <= settings.outlierZ()) {
            return Optional.empty();
        }
        Integer exponent = null;
        double best = Double.MAX_VALUE;
        for (int k = 1; k <= 3; k++) {
            int n = z > 0 ? -k : k;
            double candidate = value * Math.pow(10, n);
            double cz = Math.abs(stats.z(candidate, floor));
            if (cz <= settings.typicalZ() && cz < best) {
                best = cz;
                exponent = n;
            }
        }
        AlertType type = exponent != null ? AlertType.DECIMAL_SCALE : z > 0 ? AlertType.AMOUNT_HIGH : AlertType.AMOUNT_LOW;
        BigDecimal low = BigDecimal.valueOf(stats.low(settings.typicalZ(), floor));
        BigDecimal high = BigDecimal.valueOf(stats.high(settings.typicalZ(), floor));
        String range = "faixa típica %s a %s; referência: %s, %d valores".formatted(Brl.format(low), Brl.format(high),
                label(reference.kind()), stats.size());
        String detail;
        if (exponent != null) {
            BigDecimal suggested = amount.scaleByPowerOfTen(exponent);
            detail = "Valor %s fora do esperado (%s). %s por %s ficaria %s, dentro da faixa: provável erro de separador decimal."
                    .formatted(Brl.format(amount), range, exponent < 0 ? "Dividido" : "Multiplicado",
                            "1" + "0".repeat(Math.abs(exponent)), Brl.format(suggested));
        } else {
            detail = "Valor %s muito %s do esperado (%s).".formatted(Brl.format(amount), z > 0 ? "acima" : "abaixo", range);
        }
        int priority = type.priority() + (int) Math.min(29, Math.round(Math.abs(z)));
        return Optional.of(new AlertFinding(rowNumber, type, priority, field, amount, scale2(low), scale2(high),
                reference.kind(), stats.size(), exponent, detail));
    }

    static String label(ReferenceKind kind) {
        return switch (kind) {
            case HISTORY_PRODUCT -> "histórico do credor neste produto";
            case HISTORY_PORTFOLIO -> "histórico desta carteira";
            case HISTORY_CREDITOR -> "histórico do credor";
            case FILE_PRODUCT -> "este arquivo, mesmo produto";
            case FILE -> "este arquivo";
        };
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_EVEN);
    }
}
