package br.com.gestorcobranca.payment.domain.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookAuthenticationTest {

    private static final String SECRET = "segredo-hmac-de-teste-com-32-caracteres";

    @Test
    void hmacOverTheRawBody() {
        byte[] body = "{\"pix\":[]}".getBytes(StandardCharsets.UTF_8);
        String header = WebhookAuthentication.hmacHeader(SECRET, body);
        assertThat(header).startsWith("sha256=");
        assertThat(WebhookAuthentication.validHmac(SECRET, header, body)).isTrue();
        assertThat(WebhookAuthentication.validHmac(SECRET, header.substring(7), body)).isTrue();
        assertThat(WebhookAuthentication.validHmac(SECRET, header, "{\"pix\":[1]}".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(WebhookAuthentication.validHmac("outro", header, body)).isFalse();
        assertThat(WebhookAuthentication.validHmac(SECRET, "sha256=zz", body)).isFalse();
        assertThat(WebhookAuthentication.validHmac(SECRET, null, body)).isFalse();
        assertThat(WebhookAuthentication.validHmac(null, header, body)).isFalse();
    }

    @Test
    void mtlsRequiresPinnedFingerprintAndValidity() throws Exception {
        String pem;
        try (InputStream in = getClass().getResourceAsStream("/payment/psp-client-test.pem")) {
            pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        Instant now = Instant.now();
        // o ALB envia o PEM codificado em URL
        String header = URLEncoder.encode(pem, StandardCharsets.UTF_8);
        assertThat(WebhookAuthentication.validClientCertificate(header, List.of(fingerprint), now)).isTrue();
        assertThat(WebhookAuthentication.validClientCertificate(pem, List.of("00".repeat(32), fingerprint), now)).isTrue();
        assertThat(WebhookAuthentication.validClientCertificate(header, List.of("ab".repeat(32)), now)).isFalse();
        assertThat(WebhookAuthentication.validClientCertificate(header, List.of(fingerprint),
                certificate.getNotAfter().toInstant().plusSeconds(1))).isFalse();
        assertThat(WebhookAuthentication.validClientCertificate("lixo", List.of(fingerprint), now)).isFalse();
        assertThat(WebhookAuthentication.validClientCertificate(null, List.of(fingerprint), now)).isFalse();
        assertThat(WebhookAuthentication.validClientCertificate(header, List.of(), now)).isFalse();
    }
}
