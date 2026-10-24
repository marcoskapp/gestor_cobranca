package br.com.gestorcobranca.analytics.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros do módulo analytics ({@code app.analytics.*}).
 *
 * @param refreshInterval         intervalo entre atualizações agendadas dos snapshots
 * @param refreshStatementTimeout tempo limite das instruções da atualização (acima do padrão de 60 s do runtime)
 * @param manualRefreshMinInterval intervalo mínimo entre atualizações da mesma assessoria pedidas na tela
 * @param slowQueryThreshold      consulta de painel acima disto conta em {@code analytics.queries.slow} e gera WARN
 * @param exportMaxRows           limite de linhas de um arquivo exportado
 */
@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(Duration refreshInterval, Duration refreshStatementTimeout,
                                  Duration manualRefreshMinInterval, Duration slowQueryThreshold, Integer exportMaxRows) {

    public AnalyticsProperties {
        refreshInterval = refreshInterval == null ? Duration.ofMinutes(15) : refreshInterval;
        refreshStatementTimeout = refreshStatementTimeout == null ? Duration.ofMinutes(10) : refreshStatementTimeout;
        manualRefreshMinInterval = manualRefreshMinInterval == null ? Duration.ofMinutes(2) : manualRefreshMinInterval;
        slowQueryThreshold = slowQueryThreshold == null ? Duration.ofSeconds(2) : slowQueryThreshold;
        exportMaxRows = exportMaxRows == null ? 100_000 : exportMaxRows;
    }
}
