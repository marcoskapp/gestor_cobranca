package br.com.gestorcobranca.publicapi.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Parâmetros da API pública ({@code app.public-api.*}).
 *
 * @param tokenHashKeys            id → chave HMAC (Base64, ≥ 32 bytes) do hash dos segredos dos tokens ("pepper" do
 *                                 servidor, fora do banco); mais de uma chave permite rotação
 * @param activeTokenHashKeyId     chave usada em tokens novos e rotações
 * @param defaultRateLimitPerMinute limite padrão por token (padrão 600 req/min, por instância)
 * @param usageFlushInterval       intervalo da gravação em lote do consumo acumulado em memória (padrão 1 min)
 * @param syncStabilizationWindow  atraso da sincronização incremental: só registros alterados antes de
 *                                 {@code agora - janela} são entregues, para não pular alterações de transações ainda
 *                                 não confirmadas (padrão 1 min)
 * @param publicBaseUrl            URL pública usada no contrato OpenAPI ({@code servers})
 */
@ConfigurationProperties(prefix = "app.public-api")
public record PublicApiProperties(Map<String, String> tokenHashKeys, String activeTokenHashKeyId,
                                  int defaultRateLimitPerMinute, Duration usageFlushInterval,
                                  Duration syncStabilizationWindow, String publicBaseUrl) {

    public PublicApiProperties {
        tokenHashKeys = tokenHashKeys == null ? Map.of() : Map.copyOf(tokenHashKeys);
        activeTokenHashKeyId = activeTokenHashKeyId == null || activeTokenHashKeyId.isBlank() ? "t1" : activeTokenHashKeyId;
        defaultRateLimitPerMinute = defaultRateLimitPerMinute <= 0 ? 600 : defaultRateLimitPerMinute;
        usageFlushInterval = usageFlushInterval == null || usageFlushInterval.isNegative() || usageFlushInterval.isZero()
                ? Duration.ofMinutes(1) : usageFlushInterval;
        syncStabilizationWindow = syncStabilizationWindow == null || syncStabilizationWindow.isNegative()
                ? Duration.ofMinutes(1) : syncStabilizationWindow;
        publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? "https://localhost" : publicBaseUrl.strip();
    }
}
