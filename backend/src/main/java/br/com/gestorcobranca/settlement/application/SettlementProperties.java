package br.com.gestorcobranca.settlement.application;

import br.com.gestorcobranca.settlement.domain.service.StatementResponseDeadline;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Parâmetros do módulo ({@code app.settlement.*}).
 *
 * @param adjustmentDualApprovalThreshold ajuste do demonstrativo acima deste valor absoluto exige segunda
 *                                        aprovação (padrão R$ 1.000,00)
 * @param evidenceMaxSizeBytes            tamanho máximo do comprovante de repasse (padrão 10 MB)
 * @param statementResponseDays           dias corridos, a partir do envio, para o credor aceitar ou contestar o
 *                                        demonstrativo (padrão 10; de 1 a {@link StatementResponseDeadline#MAX_DAYS};
 *                                        só lembrete — decisão do responsável de 26/11/2026). Fora da faixa, a
 *                                        aplicação não sobe (em vez de falhar só no envio do demonstrativo).
 */
@Validated
@ConfigurationProperties(prefix = "app.settlement")
public record SettlementProperties(@DefaultValue("1000.00") BigDecimal adjustmentDualApprovalThreshold,
                                   @DefaultValue("10485760") long evidenceMaxSizeBytes,
                                   @DefaultValue("10") @Min(1) @Max(StatementResponseDeadline.MAX_DAYS)
                                   int statementResponseDays) {
}
