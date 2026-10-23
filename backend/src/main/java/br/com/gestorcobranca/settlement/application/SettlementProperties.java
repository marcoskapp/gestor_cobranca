package br.com.gestorcobranca.settlement.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Parâmetros do módulo ({@code app.settlement.*}).
 *
 * @param adjustmentDualApprovalThreshold ajuste do demonstrativo acima deste valor absoluto exige segunda
 *                                        aprovação (padrão R$ 1.000,00)
 * @param evidenceMaxSizeBytes            tamanho máximo do comprovante de repasse (padrão 10 MB)
 */
@ConfigurationProperties(prefix = "app.settlement")
public record SettlementProperties(@DefaultValue("1000.00") BigDecimal adjustmentDualApprovalThreshold,
                                   @DefaultValue("10485760") long evidenceMaxSizeBytes) {
}
