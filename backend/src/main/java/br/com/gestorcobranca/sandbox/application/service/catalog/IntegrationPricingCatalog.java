package br.com.gestorcobranca.sandbox.application.service.catalog;

import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases.IntegrationPricingCommand;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Segment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Condições de cobrança das integrações (API pública) no contrato de cada credor do sandbox: dia de fechamento do
 * ciclo de consumo variado entre credores, mensalidade, franquias e preço do excedente por segmento. Valores de
 * demonstração — não são proposta comercial.
 */
public final class IntegrationPricingCatalog {

    /** Dia de fechamento por prefixo do credor (31 = último dia do mês). */
    private static final Map<String, Integer> CLOSING_DAYS = Map.of(
            "HZN", 5, "EST", 15, "TVC", 31,
            "CSR", 10, "ELT", 20, "UMC", 31);

    private IntegrationPricingCatalog() {
    }

    public static IntegrationPricingCommand pricing(String creditorPrefix, Segment segment) {
        int closingDay = CLOSING_DAYS.getOrDefault(creditorPrefix, 31);
        String reason = "Condições de demonstração da API pública (sandbox)";
        return switch (segment) {
            // banco: volume alto, franquia grande e excedente barato
            case BANK -> new IntegrationPricingCommand(closingDay, new BigDecimal("490.00"), 100_000,
                    new BigDecimal("0.001500"), 2_000, new BigDecimal("0.050000"), 5_000, new BigDecimal("0.030000"),
                    50_000, new BigDecimal("0.008000"), reason);
            // varejo/educação: volume médio
            case RETAIL -> new IntegrationPricingCommand(closingDay, new BigDecimal("290.00"), 50_000,
                    new BigDecimal("0.002000"), 1_000, new BigDecimal("0.060000"), 2_000, new BigDecimal("0.040000"),
                    20_000, new BigDecimal("0.010000"), reason);
            // serviços (telecom/energia): mensalidade menor, franquia pequena e excedente mais caro
            case SERVICES -> new IntegrationPricingCommand(closingDay, new BigDecimal("190.00"), 20_000,
                    new BigDecimal("0.003000"), 500, new BigDecimal("0.080000"), 1_000, new BigDecimal("0.050000"),
                    10_000, new BigDecimal("0.015000"), reason);
        };
    }
}
