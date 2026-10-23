package br.com.gestorcobranca.debt.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros do módulo debt ({@code app.debt.*}). Os prazos de prescrição não são parâmetros
 * da aplicação: ficam nas regras de cada assessoria ({@code debt.prescription_rule}), assim como
 * a janela de alerta de cada regra.
 *
 * @param prescription agendamentos e lotes da prescrição
 * @param evidence     anexos de evidência das interrupções
 */
@ConfigurationProperties(prefix = "app.debt")
public record DebtProperties(Prescription prescription, Evidence evidence) {

    public DebtProperties {
        prescription = prescription == null ? new Prescription(null, null, null) : prescription;
        evidence = evidence == null ? new Evidence(null) : evidence;
    }

    /**
     * @param dailyCron          varredura diária (reconciliação e alerta de prescrição próxima), no fuso de operação
     *                           ({@code app.debt.prescription.daily-cron}, lido pelo agendador)
     * @param batchSize          títulos por transação na varredura diária
     * @param recalculationBatch contratos por execução do job de recálculo após mudança de regra
     */
    public record Prescription(String dailyCron, Integer batchSize, Integer recalculationBatch) {

        public Prescription {
            dailyCron = dailyCron == null ? "0 10 6 * * *" : dailyCron;
            batchSize = batchSize == null ? 500 : batchSize;
            recalculationBatch = recalculationBatch == null ? 200 : recalculationBatch;
        }
    }

    /** @param maxSizeBytes tamanho máximo do arquivo de evidência */
    public record Evidence(Long maxSizeBytes) {

        public Evidence {
            maxSizeBytes = maxSizeBytes == null ? 20L * 1024 * 1024 : maxSizeBytes;
        }
    }
}
