package br.com.gestorcobranca.shared.config;

import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Fallback da SPA: rotas do Angular servem index.html; API, actuator e arquivos inexistentes não. */
class SpaResourceConfigIT extends IntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void spaRoutesFallBackToIndexWithoutCache() throws Exception {
        HttpResponse<String> response = get("/carteiras/123");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<app-root>");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-cache"));
    }

    @Test
    void missingAssetsAndBackendPathsDoNotFallBack() throws Exception {
        assertThat(get("/main-ABCDEF12.js").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/inexistente").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/env").statusCode()).isIn(401, 403);
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        HttpResponse<String> response = get("/");
        assertThat(response.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    }
}
