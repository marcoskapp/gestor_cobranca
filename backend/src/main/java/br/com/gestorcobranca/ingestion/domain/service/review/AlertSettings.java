package br.com.gestorcobranca.ingestion.domain.service.review;

/**
 * Limiares dos alertas de valor ({@code app.ingestion.review.*}; método em {@code importacao.md} §4).
 *
 * @param outlierZ                 |z| robusto acima do qual o valor é atípico (Iglewicz & Hoaglin: 3,5)
 * @param typicalZ                 |z| que define a faixa típica usada no teste de escala (valor ÷/× 10ᵏ)
 * @param sigmaFloor               piso do desvio em escala log (0,5 ≈ ×1,65) para distribuições concentradas
 * @param minSample                amostra mínima para usar uma referência (histórico ou arquivo)
 * @param balanceFineRate          multa máxima aceita no saldo sem alerta (0,02 = 2%)
 * @param balanceMonthlyRate       encargos mensais máximos aceitos no saldo sem alerta (0,10 = 10% ao mês, simples)
 * @param maxFutureYears           vencimento mais distante aceito sem alerta
 * @param maxPastYears             vencimento mais antigo aceito sem alerta
 * @param debtorSpreadRatio        razão entre o título e a mediana do devedor que gera alerta
 * @param contractOverRatio        soma das parcelas acima de N × o valor do contrato gera alerta
 * @param fileIntegerShare         fração de valores sem centavos no arquivo que caracteriza "centavos sempre zerados"
 * @param historyIntegerShare      fração máxima de valores sem centavos no histórico para o alerta de centavos
 * @param fileShiftTolerance       desvio relativo em torno de 10ⁿ para a mediana do arquivo × histórico
 */
public record AlertSettings(double outlierZ, double typicalZ, double sigmaFloor, int minSample, double balanceFineRate,
                            double balanceMonthlyRate, int maxFutureYears, int maxPastYears, double debtorSpreadRatio,
                            double contractOverRatio, double fileIntegerShare, double historyIntegerShare,
                            double fileShiftTolerance) {

    public static AlertSettings defaults() {
        return new AlertSettings(3.5, 2.0, 0.5, 30, 0.02, 0.10, 10, 20, 100, 2.0, 0.99, 0.5, 0.25);
    }
}
