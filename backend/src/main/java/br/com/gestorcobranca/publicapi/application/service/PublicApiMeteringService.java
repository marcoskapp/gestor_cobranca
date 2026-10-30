package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.gateway.PublicApiMetering;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Medição de cada requisição autenticada da API pública, em três fluxos separados:
 * <ol>
 *   <li><b>acesso</b> — uma linha por requisição no log de acesso ({@code PUBLIC_API_REQUEST}: token, credor, rota,
 *       método, status, bytes, latência; IP, request-id e assessoria vêm do contexto), rastreabilidade;</li>
 *   <li><b>técnico</b> — métricas Micrometer/CloudWatch por rota e status (sem credor/token nas tags, para não
 *       explodir a cardinalidade);</li>
 *   <li><b>consumo</b> — soma em memória ({@link UsageAccumulator}) para a gravação em lote por hora, insumo do
 *       faturamento.</li>
 * </ol>
 * Nenhuma escrita de consumo acontece no caminho da requisição; o log de acesso usa a gravação própria já existente.
 */
@Service
class PublicApiMeteringService implements PublicApiMetering {

    private final UsageAccumulator accumulator;
    private final AccessLogger accessLogger;
    private final MeterRegistry meterRegistry;

    PublicApiMeteringService(UsageAccumulator accumulator, AccessLogger accessLogger, MeterRegistry meterRegistry) {
        this.accumulator = accumulator;
        this.accessLogger = accessLogger;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void record(RequestMeasurement m) {
        accumulator.add(m.tenantId(), m.creditorId(), m.tokenId(), m.at(), m.method(), m.route(), m.status(),
                m.bytesIn(), m.bytesOut(), m.records(), m.latencyMs());
        accumulator.touch(m.tenantId(), m.tokenId(), m.at(), m.clientIp());

        String outcome = m.status() == 429 ? "throttled" : m.status() >= 500 ? "server_error"
                : m.status() >= 400 ? "client_error" : "success";
        Timer.builder("publicapi.requests").tag("method", m.method()).tag("route", m.route())
                .tag("status", String.valueOf(m.status())).tag("outcome", outcome)
                .publishPercentileHistogram().register(meterRegistry).record(Duration.ofMillis(m.latencyMs()));
        DistributionSummary.builder("publicapi.bytes").baseUnit("bytes").tag("direction", "in").tag("route", m.route())
                .register(meterRegistry).record(m.bytesIn());
        DistributionSummary.builder("publicapi.bytes").baseUnit("bytes").tag("direction", "out").tag("route", m.route())
                .register(meterRegistry).record(m.bytesOut());
        if (m.records() > 0) {
            meterRegistry.counter("publicapi.records", "route", m.route()).increment(m.records());
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("tokenPrefix", m.tokenPrefix());
        details.put("creditorId", m.creditorId().toString());
        details.put("method", m.method());
        details.put("status", String.valueOf(m.status()));
        details.put("bytesIn", String.valueOf(m.bytesIn()));
        details.put("bytesOut", String.valueOf(m.bytesOut()));
        details.put("latencyMs", String.valueOf(m.latencyMs()));
        if (m.records() > 0) {
            details.put("records", String.valueOf(m.records()));
        }
        accessLogger.log(new AccessEvent(AccessEventType.PUBLIC_API_REQUEST, m.status() < 400, m.tenantId(),
                "INTEGRATION", m.tokenId(), null, m.tokenPrefix(), details));
    }
}
