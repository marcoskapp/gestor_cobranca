package br.com.gestorcobranca.compliance.application;

import br.com.gestorcobranca.compliance.domain.model.RequestType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Parâmetros do módulo compliance ({@code app.compliance.*}).
 *
 * @param dataSubjectRequest prazos de atendimento das solicitações de titulares
 * @param retention          execução da política de retenção
 * @param evidence           anexos de evidência
 */
@ConfigurationProperties(prefix = "app.compliance")
public record ComplianceProperties(DataSubjectRequest dataSubjectRequest, Retention retention, Evidence evidence) {

    public ComplianceProperties {
        dataSubjectRequest = dataSubjectRequest == null ? new DataSubjectRequest(null, null, null) : dataSubjectRequest;
        retention = retention == null ? new Retention(null, null, null, null) : retention;
        evidence = evidence == null ? new Evidence(null) : evidence;
    }

    /**
     * @param defaultDueDays dias corridos para atender (padrão 15, LGPD art. 19, II)
     * @param dueDays        prazo específico por tipo de solicitação
     * @param overdueCheckInterval intervalo da verificação de solicitações vencidas
     */
    public record DataSubjectRequest(Integer defaultDueDays, Map<RequestType, Integer> dueDays,
                                     Duration overdueCheckInterval) {

        public DataSubjectRequest {
            defaultDueDays = defaultDueDays == null ? 15 : defaultDueDays;
            dueDays = dueDays == null ? Map.of() : Map.copyOf(dueDays);
            overdueCheckInterval = overdueCheckInterval == null ? Duration.ofMinutes(30) : overdueCheckInterval;
        }
    }

    /**
     * @param cron           agendamento da execução automática, no fuso de operação
     *                       ({@code app.compliance.retention.cron}, lido pelo agendador)
     * @param batchSize      devedores avaliados por lote (cada anonimização em transação própria)
     * @param staleRunAfter  execução em andamento há mais tempo que isto é considerada interrompida
     *                       (instância encerrada no meio) e liberada para nova execução
     * @param expiryCheckInterval intervalo da verificação de restrições temporárias encerradas
     */
    public record Retention(String cron, Integer batchSize, Duration staleRunAfter, Duration expiryCheckInterval) {

        public Retention {
            cron = cron == null ? "0 30 2 * * *" : cron;
            batchSize = batchSize == null ? 200 : batchSize;
            staleRunAfter = staleRunAfter == null ? Duration.ofHours(6) : staleRunAfter;
            expiryCheckInterval = expiryCheckInterval == null ? Duration.ofMinutes(5) : expiryCheckInterval;
        }
    }

    /** @param maxSizeBytes tamanho máximo do arquivo de evidência */
    public record Evidence(Long maxSizeBytes) {

        public Evidence {
            maxSizeBytes = maxSizeBytes == null ? 20L * 1024 * 1024 : maxSizeBytes;
        }
    }
}
