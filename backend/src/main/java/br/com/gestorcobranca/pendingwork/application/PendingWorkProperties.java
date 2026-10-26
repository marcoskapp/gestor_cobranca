package br.com.gestorcobranca.pendingwork.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros de "Minhas pendências" ({@code app.pending-work.*}).
 *
 * @param timeout                 tempo máximo da consulta inteira; também o {@code statement_timeout} de cada fonte
 * @param executorSize            fontes consultadas em paralelo (todas as requisições da instância compartilham
 *                                o executor — limita as conexões de banco usadas por esta tela)
 * @param queueCapacity           fontes aguardando execução; acima disso a fonte é marcada como {@code BUSY}
 * @param dueSoonDays             janela de "perto do prazo" (dias corridos) das fontes que só mostram o que vence em
 *                                breve
 * @param observationRetentionDays dias de retenção do registro diário de observações (dedup do log de negócio)
 */
@ConfigurationProperties(prefix = "app.pending-work")
public record PendingWorkProperties(Duration timeout, Integer executorSize, Integer queueCapacity, Integer dueSoonDays,
                                    Integer observationRetentionDays) {

    public PendingWorkProperties {
        timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
        executorSize = executorSize == null ? 4 : executorSize;
        queueCapacity = queueCapacity == null ? 500 : queueCapacity;
        dueSoonDays = dueSoonDays == null ? 3 : dueSoonDays;
        observationRetentionDays = observationRetentionDays == null ? 30 : observationRetentionDays;
        if (timeout.isNegative() || timeout.isZero() || executorSize < 1 || queueCapacity < 0 || dueSoonDays < 0
                || observationRetentionDays < 1) {
            throw new IllegalArgumentException("Parâmetros inválidos em app.pending-work");
        }
    }
}
