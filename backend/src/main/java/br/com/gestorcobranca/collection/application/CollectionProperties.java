package br.com.gestorcobranca.collection.application;

import br.com.gestorcobranca.collection.domain.model.CollectionSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros do módulo collection ({@code app.collection.*}).
 *
 * @param defaults             parâmetros da operação enquanto a assessoria não grava os seus
 * @param callbackCheckInterval intervalo do job de vencimento de retornos
 * @param dailyCron            job diário (fuso de operação): quebra de promessas e prazo de contestações
 * @param batchSize            casos/devedores por job de sincronização, roteamento e recálculo
 * @param evidence             anexos de evidência de contestações
 */
@ConfigurationProperties(prefix = "app.collection")
public record CollectionProperties(Defaults defaults, Duration callbackCheckInterval, String dailyCron,
                                   Integer batchSize, Evidence evidence) {

    public CollectionProperties {
        defaults = defaults == null ? new Defaults(null, null, null, null) : defaults;
        callbackCheckInterval = callbackCheckInterval == null ? Duration.ofMinutes(5) : callbackCheckInterval;
        dailyCron = dailyCron == null ? "0 10 0 * * *" : dailyCron;
        batchSize = batchSize == null ? 200 : batchSize;
        evidence = evidence == null ? new Evidence(null) : evidence;
    }

    public record Defaults(Integer promiseToleranceDays, Integer promiseMaxDays, Integer callbackToleranceMinutes,
                           Integer disputeResponseDays) {

        public Defaults {
            promiseToleranceDays = promiseToleranceDays == null ? 2 : promiseToleranceDays;
            promiseMaxDays = promiseMaxDays == null ? 30 : promiseMaxDays;
            callbackToleranceMinutes = callbackToleranceMinutes == null ? 30 : callbackToleranceMinutes;
            disputeResponseDays = disputeResponseDays == null ? 10 : disputeResponseDays;
        }

        public CollectionSettings.Values values() {
            return new CollectionSettings.Values(promiseToleranceDays, promiseMaxDays, callbackToleranceMinutes,
                    disputeResponseDays);
        }
    }

    /** @param maxSizeBytes tamanho máximo do arquivo de evidência */
    public record Evidence(Long maxSizeBytes) {

        public Evidence {
            maxSizeBytes = maxSizeBytes == null ? 20L * 1024 * 1024 : maxSizeBytes;
        }
    }
}
