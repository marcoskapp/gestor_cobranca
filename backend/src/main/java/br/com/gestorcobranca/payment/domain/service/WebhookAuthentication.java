package br.com.gestorcobranca.payment.domain.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Autenticação das notificações de PSP (webhook Pix), sempre antes de qualquer interpretação do corpo:
 * <ul>
 *   <li><b>mTLS</b> (padrão da API Pix): o ALB termina o TLS mútuo verificando a cadeia contra o
 *       trust store dos PSPs e repassa o certificado folha em cabeçalho (PEM codificado em URL); aqui
 *       se exige que a impressão digital SHA-256 do certificado seja uma das fixadas na conta e que ele
 *       esteja dentro da validade;</li>
 *   <li><b>HMAC</b> (PSPs que assinam o corpo): {@code sha256=<hex>} do HMAC-SHA256 do corpo bruto com o
 *       segredo da conta.</li>
 * </ul>
 * Comparações em tempo constante.
 */
public final class WebhookAuthentication {

    private WebhookAuthentication() {
    }

    public static boolean validHmac(String secret, String signatureHeader, byte[] body) {
        if (secret == null || secret.isBlank() || signatureHeader == null || body == null) {
            return false;
        }
        String provided = signatureHeader.trim();
        if (provided.toLowerCase(Locale.ROOT).startsWith("sha256=")) {
            provided = provided.substring(7);
        }
        byte[] expected = hmacSha256(secret, body);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(provided.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    public static String hmacHeader(String secret, byte[] body) {
        return "sha256=" + HexFormat.of().formatHex(hmacSha256(secret, body));
    }

    /**
     * @param certificateHeader certificado folha em PEM (opcionalmente codificado em URL, como o ALB envia)
     * @param pinned            impressões digitais SHA-256 (hex minúsculo) aceitas
     */
    public static boolean validClientCertificate(String certificateHeader, Collection<String> pinned, Instant now) {
        if (certificateHeader == null || certificateHeader.isBlank() || pinned == null || pinned.isEmpty()) {
            return false;
        }
        X509Certificate certificate;
        try {
            String pem = certificateHeader.contains("%") ? URLDecoder.decode(certificateHeader, StandardCharsets.UTF_8)
                    : certificateHeader;
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            certificate.checkValidity(java.util.Date.from(now));
        } catch (CertificateException | IllegalArgumentException | ClassCastException e) {
            return false;
        }
        byte[] fingerprint;
        try {
            fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        } catch (GeneralSecurityException e) {
            return false;
        }
        boolean match = false;
        for (String candidate : pinned) {
            byte[] expected;
            try {
                expected = HexFormat.of().parseHex(candidate);
            } catch (IllegalArgumentException e) {
                continue;
            }
            match |= MessageDigest.isEqual(expected, fingerprint);
        }
        return match;
    }

    private static byte[] hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
