package br.com.gestorcobranca.shared.web;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP do cliente pela conexão real (Tomcat), com 127.0.0.1 no papel do ALB (proxy confiável nos
 * testes): o {@code X-Forwarded-For} forjado à esquerda e o {@code Forwarded} não mudam o IP usado no
 * limite de login nem o gravado no log de acesso; um IP inválido não descarta o evento.
 */
class ClientAddressIT extends IntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    JdbcTemplate jdbcTemplate;

    final HttpClient http = HttpClient.newHttpClient();
    UUID tenant;
    String slug;

    @BeforeEach
    void setUp() {
        slug = "ip-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "IP Ltda", "IP", "11.444.777/0001-61")).id());
    }

    @Test
    void forgedForwardedHeadersDoNotEscapeTheLoginRateLimitNorReachTheAccessLog() throws Exception {
        String realClient = "203.0.113." + ThreadLocalRandom.current().nextInt(1, 250);
        awayFromMinuteBoundary();
        List<Integer> statuses = new ArrayList<>();
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String forged = "9.9." + (i / 250) + "." + (i % 250 + 1);
            String requestId = UUID.randomUUID().toString();
            requestIds.add(requestId);
            // o cliente manda um XFF e um Forwarded próprios; o ALB acrescenta o IP real à direita
            statuses.add(login(Map.of("X-Forwarded-For", forged + ", " + realClient,
                    "Forwarded", "for=" + forged, "X-Real-IP", forged, "X-Request-Id", requestId)));
        }
        assertThat(statuses.subList(0, 20)).allMatch(s -> s == 401);
        assertThat(statuses.subList(20, 24)).containsOnly(429);

        List<String> ips = tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForList("""
                select host(client_ip) from shared.access_log
                 where tenant_id = ? and event_type = 'LOGIN_FAILURE' and request_id = any(?)""",
                String.class, tenant, requestIds.toArray(String[]::new)));
        assertThat(ips).hasSize(20).containsOnly(realClient);
    }

    @Test
    void invalidForwardedValueIsLoggedWithoutIpInsteadOfDroppingTheEvent() throws Exception {
        String requestId = UUID.randomUUID().toString();
        assertThat(login(Map.of("X-Forwarded-For", "not-an-ip", "X-Request-Id", requestId))).isEqualTo(401);

        Map<String, Object> row = tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForMap("""
                select client_ip, details->>'clientIpInvalid' as invalid from shared.access_log
                 where tenant_id = ? and event_type = 'LOGIN_FAILURE' and request_id = ?""", tenant, requestId));
        assertThat(row.get("client_ip")).isNull();
        assertThat(row.get("invalid")).isEqualTo("true");
    }

    @Test
    void withoutForwardedHeaderTheConnectionAddressIsUsed() throws Exception {
        String requestId = UUID.randomUUID().toString();
        assertThat(login(Map.of("X-Request-Id", requestId))).isEqualTo(401);
        String ip = tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject("""
                select host(client_ip) from shared.access_log where tenant_id = ? and request_id = ?""",
                String.class, tenant, requestId));
        assertThat(ip).isIn("127.0.0.1", "::1");
    }

    /** O limite é por janela de 1 minuto do relógio: começa longe da virada para não dividir as tentativas. */
    private static void awayFromMinuteBoundary() throws InterruptedException {
        long second = (System.currentTimeMillis() / 1000) % 60;
        if (second > 40) {
            Thread.sleep((61 - second) * 1000);
        }
    }

    private int login(Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"tenant":"%s","email":"ninguem@teste.com","password":"Senha-Errada-123"}""".formatted(slug)));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
