package br.com.gestorcobranca.ingestion.application;

import br.com.gestorcobranca.ingestion.domain.model.review.ImportReview;
import br.com.gestorcobranca.ingestion.domain.service.review.AlertSettings;
import br.com.gestorcobranca.ingestion.domain.service.review.ControlTotals;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Parâmetros da conferência da importação ({@code app.ingestion.review.*}; regras em
 * {@code docs/tecnico/desenho/importacao.md}).
 *
 * @param countTolerance        diferença aceita na quantidade de linhas (padrão 0)
 * @param amountTolerance       diferença aceita nas somas, por arredondamento (padrão R$ 0,01)
 * @param scaleTolerance        desvio relativo em torno de 10ⁿ para apontar erro de separador (padrão 0,5%)
 * @param approvalThreshold     soma do principal a importar a partir da qual a efetivação exige aprovação de segunda
 *                              pessoa com {@code IMPORT_APPROVE} (padrão R$ 1.000.000,00; zero desativa)
 * @param maxUnreviewedAlerts   alertas abertos tolerados na efetivação (padrão 10)
 * @param requireScaleReview    alertas de escala (provável separador decimal) precisam ser todos revisados (padrão sim)
 * @param maxAlerts             alertas guardados por prévia; acima disso a efetivação é bloqueada (padrão 10.000)
 * @param confirmationText      palavra de confirmação da efetivação (padrão IMPORTAR)
 * @param outlierZ              |z| robusto que caracteriza valor atípico (padrão 3,5)
 * @param typicalZ              |z| da faixa típica usada no teste de escala (padrão 2)
 * @param sigmaFloor            piso do desvio em escala log (padrão 0,5)
 * @param minSample             amostra mínima de uma referência (padrão 30)
 * @param balanceFineRate       multa aceita no saldo sem alerta (padrão 2%)
 * @param balanceMonthlyRate    encargos mensais aceitos no saldo sem alerta (padrão 10% ao mês)
 * @param maxFutureYears        vencimento futuro máximo sem alerta (padrão 10 anos)
 * @param maxPastYears          vencimento antigo máximo sem alerta (padrão 20 anos)
 * @param debtorSpreadRatio     razão título ÷ mediana do devedor que gera alerta (padrão 100)
 * @param contractOverRatio     soma das parcelas ÷ valor do contrato que gera alerta (padrão 2)
 */
@ConfigurationProperties(prefix = "app.ingestion.review")
public record ReviewProperties(@DefaultValue("0") long countTolerance,
                               @DefaultValue("0.01") BigDecimal amountTolerance,
                               @DefaultValue("0.005") double scaleTolerance,
                               @DefaultValue("1000000.00") BigDecimal approvalThreshold,
                               @DefaultValue("10") int maxUnreviewedAlerts,
                               @DefaultValue("true") boolean requireScaleReview,
                               @DefaultValue("10000") int maxAlerts,
                               @DefaultValue("IMPORTAR") String confirmationText,
                               @DefaultValue("3.5") double outlierZ,
                               @DefaultValue("2.0") double typicalZ,
                               @DefaultValue("0.5") double sigmaFloor,
                               @DefaultValue("30") int minSample,
                               @DefaultValue("0.02") double balanceFineRate,
                               @DefaultValue("0.10") double balanceMonthlyRate,
                               @DefaultValue("10") int maxFutureYears,
                               @DefaultValue("20") int maxPastYears,
                               @DefaultValue("100") double debtorSpreadRatio,
                               @DefaultValue("2.0") double contractOverRatio) {

    public ControlTotals controlTotals() {
        return new ControlTotals(countTolerance, amountTolerance, scaleTolerance);
    }

    public AlertSettings alertSettings() {
        AlertSettings d = AlertSettings.defaults();
        return new AlertSettings(outlierZ, typicalZ, sigmaFloor, minSample, balanceFineRate, balanceMonthlyRate,
                maxFutureYears, maxPastYears, debtorSpreadRatio, contractOverRatio, d.fileIntegerShare(),
                d.historyIntegerShare(), d.fileShiftTolerance());
    }

    public ImportReview.Limits limits() {
        return new ImportReview.Limits(maxUnreviewedAlerts, requireScaleReview);
    }

    /** Limite de aprovação efetivo (nulo = sem aprovação de segunda pessoa). */
    public BigDecimal effectiveApprovalThreshold() {
        return approvalThreshold == null || approvalThreshold.signum() <= 0 ? null : approvalThreshold;
    }

    /** Valores padrão (testes). */
    public static ReviewProperties defaults() {
        return new ReviewProperties(0, new BigDecimal("0.01"), 0.005, new BigDecimal("1000000.00"), 10, true, 10_000,
                "IMPORTAR", 3.5, 2.0, 0.5, 30, 0.02, 0.10, 10, 20, 100, 2.0);
    }
}
