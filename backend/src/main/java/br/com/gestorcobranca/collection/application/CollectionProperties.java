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
 * @param disputes             rotina de contestações (follow-up do credor e link seguro das evidências)
 */
@ConfigurationProperties(prefix = "app.collection")
public record CollectionProperties(Defaults defaults, Duration callbackCheckInterval, String dailyCron,
                                   Integer batchSize, Evidence evidence, Disputes disputes) {

    public CollectionProperties {
        defaults = defaults == null ? new Defaults(null, null, null, null) : defaults;
        callbackCheckInterval = callbackCheckInterval == null ? Duration.ofMinutes(5) : callbackCheckInterval;
        dailyCron = dailyCron == null ? "0 10 0 * * *" : dailyCron;
        batchSize = batchSize == null ? 200 : batchSize;
        evidence = evidence == null ? new Evidence(null) : evidence;
        disputes = disputes == null ? new Disputes(null, null, null, null) : disputes;
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

    /**
     * @param followUpCron           acompanhamento diário dos pedidos ao credor (lembrete e retorno vencido), no fuso de
     *                               operação — em horário comercial, para o credor receber no expediente
     * @param linkMaxDownloads       downloads permitidos por link seguro de evidências
     * @param linkRequestsPerMinute  limite de acessos ao link por IP e minuto, por instância
     * @param mailMaxAttempts        tentativas de envio de cada e-mail ao credor
     */
    public record Disputes(String followUpCron, Integer linkMaxDownloads, Integer linkRequestsPerMinute,
                           Integer mailMaxAttempts) {

        public Disputes {
            followUpCron = followUpCron == null ? "0 5 8 * * *" : followUpCron;
            linkMaxDownloads = linkMaxDownloads == null ? 20 : linkMaxDownloads;
            linkRequestsPerMinute = linkRequestsPerMinute == null ? 30 : linkRequestsPerMinute;
            mailMaxAttempts = mailMaxAttempts == null ? 6 : mailMaxAttempts;
        }
    }

    /** @param maxSizeBytes tamanho máximo do arquivo de evidência */
    public record Evidence(Long maxSizeBytes) {

        public Evidence {
            maxSizeBytes = maxSizeBytes == null ? 20L * 1024 * 1024 : maxSizeBytes;
        }
    }
}
