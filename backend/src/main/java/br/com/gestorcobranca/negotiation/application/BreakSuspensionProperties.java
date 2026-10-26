package br.com.gestorcobranca.negotiation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Suspensão da quebra por pagamento em revisão ({@code app.negotiation.break-suspension.*}).
 *
 * @param maxDays   prazo máximo de suspensão em dias corridos a partir do início (padrão 7, 1–60): depois
 *                  dele a quebra volta a valer e o pagamento fica em destaque na fila de revisão
 * @param graceDays carência depois da recusa, em dias corridos a partir do dia da recusa (padrão 3, 0–30;
 *                  0 = a quebra vale já na próxima execução do job)
 */
@ConfigurationProperties(prefix = "app.negotiation.break-suspension")
public record BreakSuspensionProperties(Integer maxDays, Integer graceDays) {

    public BreakSuspensionProperties {
        maxDays = maxDays == null ? 7 : maxDays;
        graceDays = graceDays == null ? 3 : graceDays;
        if (maxDays < 1 || maxDays > 60) {
            throw new IllegalArgumentException("app.negotiation.break-suspension.max-days deve estar entre 1 e 60");
        }
        if (graceDays < 0 || graceDays > 30) {
            throw new IllegalArgumentException("app.negotiation.break-suspension.grace-days deve estar entre 0 e 30");
        }
    }
}
