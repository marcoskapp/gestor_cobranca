package br.com.gestorcobranca.incentive.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Parâmetros do módulo incentive ({@code app.incentive.*}).
 *
 * @param dualApprovalThreshold   ajuste manual com valor absoluto acima disto exige segunda aprovação
 * @param autoClose               fecha automaticamente os períodos cuja carência terminou
 * @param defaultGraceBusinessDays carência quando nenhum plano vigente a define
 * @param dailyCron               liberação de carência e fechamento automático (fuso de operação)
 * @param loggedTimeSyncInterval  sincronização do tempo logado a partir das sessões
 * @param postingSweepInterval    varredura de pagamentos ainda sem lançamento (contingência)
 * @param postingSweepMinAge      idade mínima do pagamento sem lançamento para a varredura
 */
@ConfigurationProperties(prefix = "app.incentive")
public record IncentiveProperties(BigDecimal dualApprovalThreshold, Boolean autoClose, Integer defaultGraceBusinessDays,
                                  String dailyCron, Duration loggedTimeSyncInterval, Duration postingSweepInterval,
                                  Duration postingSweepMinAge) {

    public IncentiveProperties {
        dualApprovalThreshold = dualApprovalThreshold == null ? new BigDecimal("500.00") : dualApprovalThreshold;
        autoClose = autoClose == null || autoClose;
        defaultGraceBusinessDays = defaultGraceBusinessDays == null ? 5 : defaultGraceBusinessDays;
        dailyCron = dailyCron == null ? "0 20 3 * * *" : dailyCron;
        loggedTimeSyncInterval = loggedTimeSyncInterval == null ? Duration.ofHours(1) : loggedTimeSyncInterval;
        postingSweepInterval = postingSweepInterval == null ? Duration.ofMinutes(15) : postingSweepInterval;
        postingSweepMinAge = postingSweepMinAge == null ? Duration.ofMinutes(10) : postingSweepMinAge;
    }
}
