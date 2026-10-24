package br.com.gestorcobranca.ingestion.domain.model.review;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Totais informados pelo usuário (ou pela integração). Cada um é opcional; ao menos um é exigido.
 *
 * @param rows quantidade de linhas de dados
 */
public record ControlTotalsInput(Long rows, BigDecimal principal, BigDecimal balance, BigDecimal contractAmount) {

    public ControlTotalsInput {
        Guard.require(rows != null || principal != null || balance != null || contractAmount != null,
                "import_review.totals_required", "Informe ao menos um total da sua planilha");
        Guard.require(rows == null || rows >= 0, "import_review.totals_invalid", "Quantidade de linhas inválida");
        for (BigDecimal amount : new BigDecimal[]{principal, balance, contractAmount}) {
            Guard.require(amount == null || amount.scale() <= 2 || amount.stripTrailingZeros().scale() <= 2,
                    "import_review.totals_invalid", "Informe os totais com até 2 casas decimais");
        }
    }

    /** Valores informados por medida (somente os presentes). */
    public Map<TotalsMeasure, BigDecimal> entered() {
        Map<TotalsMeasure, BigDecimal> map = new EnumMap<>(TotalsMeasure.class);
        if (rows != null) {
            map.put(TotalsMeasure.ROWS, BigDecimal.valueOf(rows));
        }
        if (principal != null) {
            map.put(TotalsMeasure.PRINCIPAL, principal);
        }
        if (balance != null) {
            map.put(TotalsMeasure.BALANCE, balance);
        }
        if (contractAmount != null) {
            map.put(TotalsMeasure.CONTRACT_AMOUNT, contractAmount);
        }
        return map;
    }
}
