package br.com.gestorcobranca.publicapi.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;

/**
 * Condições de preço aplicadas a um ciclo de consumo, copiadas da versão das condições de cobrança das integrações do
 * contrato do credor (módulo organization): mensalidade fixa por ciclo, franquias e preços unitários do excedente.
 * Quantidades de tráfego em MB inteiros (1 MB = 1.048.576 bytes). Valores cobrados arredondados a centavos
 * (HALF_EVEN) só no fim do cálculo de cada linha.
 */
public record PriceTerms(BigDecimal monthlyFee, long includedRequests, BigDecimal pricePerRequest, long includedMbIn,
                         BigDecimal pricePerMbIn, long includedMbOut, BigDecimal pricePerMbOut, long includedRecords,
                         BigDecimal pricePerRecord) {

    public PriceTerms {
        nonNegative(monthlyFee, "monthly_fee");
        nonNegative(pricePerRequest, "price_per_request");
        nonNegative(pricePerMbIn, "price_per_mb_in");
        nonNegative(pricePerMbOut, "price_per_mb_out");
        nonNegative(pricePerRecord, "price_per_record");
        Guard.require(includedRequests >= 0 && includedMbIn >= 0 && includedMbOut >= 0 && includedRecords >= 0,
                "price_terms.allowance_invalid", "Franquias devem ser zero ou positivas");
    }

    private static void nonNegative(BigDecimal value, String field) {
        Guard.require(value != null && value.signum() >= 0, "price_terms.value_invalid",
                "Valor de %s deve ser zero ou positivo".formatted(field));
    }
}
