package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.ingestion.support.pdf.PdfFixtures;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Envio de PDF pela API pública: a importação fica aguardando validação humana (nunca é importada pela API),
 * idempotente por token, só para carteiras do credor do token e sem dados do devedor na resposta.
 */
class PublicApiPdfImportIT extends PublicApiTestSupport {

    private HttpResponse<String> uploadPdf(String token, UUID portfolioId, byte[] pdf, String key)
            throws IOException, InterruptedException {
        String boundary = "----gc" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"historico.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(pdf);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/public-api/v1/imports/pdf?portfolioId=" + portfolioId))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pdfWaitsForHumanValidationAndIsIdempotentPerToken() throws Exception {
        IssuedToken token = issue(creditorA, Set.of("imports:read", "imports:write"), null, List.of());
        byte[] pdf = PdfFixtures.history(PdfFixtures.historyTitles(4), null);

        HttpResponse<String> sent = uploadPdf(token.token(), portfolioA, pdf, "pdf-carga-0001");
        assertThat(sent.statusCode()).isEqualTo(202);
        Map<String, Object> created = body(sent);
        assertThat(created).containsEntry("status", "RECEIVED").containsEntry("awaitingHumanValidation", true)
                .containsEntry("textLayer", true).containsEntry("pages", 1);
        // nenhum dado do devedor na resposta
        assertThat(sent.body()).doesNotContain("COMERCIO FICTICIO").doesNotContain(PdfFixtures.HISTORY_CNPJ.substring(0, 6));

        HttpResponse<String> again = uploadPdf(token.token(), portfolioA, pdf, "pdf-carga-0001");
        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(body(again)).containsEntry("id", created.get("id"));

        HttpResponse<String> status = get("/public-api/v1/imports/pdf/" + created.get("id"), token.token());
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(body(status)).containsEntry("id", created.get("id"));

        // sem Idempotency-Key, de outro credor ou sem escopo
        assertThat(uploadPdf(token.token(), portfolioA, pdf, null).statusCode()).isEqualTo(422);
        assertThat(uploadPdf(token.token(), portfolioB, pdf, "pdf-carga-0002").statusCode()).isEqualTo(404);
        IssuedToken other = issue(creditorB, Set.of("imports:read", "imports:write"), null, List.of());
        assertThat(get("/public-api/v1/imports/pdf/" + created.get("id"), other.token()).statusCode()).isEqualTo(404);
        IssuedToken readOnly = issue(creditorA, Set.of("imports:read"), null, List.of());
        assertThat(uploadPdf(readOnly.token(), portfolioA, pdf, "pdf-carga-0003").statusCode()).isEqualTo(403);
    }
}
