package br.com.gestorcobranca.costing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros do módulo costing ({@code app.costing.*}).
 *
 * @param refreshDays             dias reprocessados pela apuração agendada (até ontem), para alcançar lançamentos
 *                                tardios (tabulação, comissão, custas)
 * @param refreshStatementTimeout tempo limite das instruções da apuração (acima do padrão de 60 s do runtime)
 * @param maxRefreshDays          maior período de uma reapuração pedida na tela (93 dias: um trimestre)
 * @param maxPeriodDays           maior período do painel e da exportação
 * @param chatReplyWindow         tempo máximo de trabalho atribuído a cada mensagem escrita por uma pessoa no chat
 *                                (do evento anterior da conversa até o envio; desenho §4.1)
 */
@ConfigurationProperties(prefix = "app.costing")
public record CostingProperties(Integer refreshDays, Duration refreshStatementTimeout, Integer maxRefreshDays,
                                Integer maxPeriodDays, Duration chatReplyWindow) {

    public CostingProperties {
        refreshDays = refreshDays == null ? 35 : refreshDays;
        refreshStatementTimeout = refreshStatementTimeout == null ? Duration.ofMinutes(10) : refreshStatementTimeout;
        maxRefreshDays = maxRefreshDays == null ? 93 : maxRefreshDays;
        maxPeriodDays = maxPeriodDays == null ? 731 : maxPeriodDays;
        chatReplyWindow = chatReplyWindow == null ? Duration.ofMinutes(3) : chatReplyWindow;
    }
}
