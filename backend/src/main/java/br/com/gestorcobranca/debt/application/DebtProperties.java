package br.com.gestorcobranca.debt.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros do módulo debt ({@code app.debt.*}). Os prazos de prescrição não são parâmetros
 * da aplicação: ficam nas regras de cada assessoria ({@code debt.prescription_rule}), assim como
 * a janela de alerta de cada regra.
 *
 * @param prescription agendamentos e lotes da prescrição
 * @param evidence         anexos de evidência das interrupções e das correções de valor
 * @param amountAdjustment limite acumulado e proposta de aumento do limite da correção de valor
 */
@ConfigurationProperties(prefix = "app.debt")
public record DebtProperties(Prescription prescription, Evidence evidence, AmountAdjustment amountAdjustment) {

    public DebtProperties {
        prescription = prescription == null ? new Prescription(null, null, null) : prescription;
        evidence = evidence == null ? new Evidence(null) : evidence;
        amountAdjustment = amountAdjustment == null ? new AmountAdjustment(null, null, null) : amountAdjustment;
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

    /**
     * Correção de valor do título (decisões do responsável sobre as 864 e 865).
     *
     * @param cumulativeWindow    janela do limite acumulado por título, em dias inteiros: as reduções aplicadas sem segunda pessoa no mesmo
     *                            título nesse período somam com a atual ({@code app.debt.amount-adjustment.cumulative-window},
     *                            padrão 30 dias)
     * @param proposalValidity    prazo, em dias inteiros, para outra pessoa confirmar o aumento do limite; depois, a
     *                            proposta vence
     *                            ({@code proposal-validity}, padrão 15 dias)
     * @param proposalExpiryCron  descarte diário das propostas vencidas, no fuso de operação ({@code proposal-expiry-cron},
     *                            lido pelo agendador)
     */
    public record AmountAdjustment(Duration cumulativeWindow, Duration proposalValidity, String proposalExpiryCron) {

        public AmountAdjustment {
            cumulativeWindow = cumulativeWindow == null ? Duration.ofDays(30) : cumulativeWindow;
            proposalValidity = proposalValidity == null ? Duration.ofDays(15) : proposalValidity;
            proposalExpiryCron = proposalExpiryCron == null ? "0 50 3 * * *" : proposalExpiryCron;
            // em dias inteiros: telas, manual e tour falam em dias (decisões 889 e 891)
            requireWholeDays(cumulativeWindow, "app.debt.amount-adjustment.cumulative-window");
            requireWholeDays(proposalValidity, "app.debt.amount-adjustment.proposal-validity");
        }

        private static void requireWholeDays(Duration value, String property) {
            if (value.isNegative() || value.isZero() || value.toSeconds() % Duration.ofDays(1).toSeconds() != 0
                    || value.getNano() != 0) {
                throw new IllegalArgumentException(property + " deve ser um número positivo de dias inteiros (ex.: P30D)");
            }
        }
    }
}
