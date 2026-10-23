package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;

/**
 * Validação do certificado de cliente mTLS do PSP (PKCS#12 com chave privada): deve abrir com a senha
 * informada, conter uma chave privada com certificado X.509 e estar dentro da validade. Devolve a
 * impressão digital SHA-256 e o fim da validade (exibidos e auditados no lugar do certificado).
 */
public final class ClientCertificates {

    private ClientCertificates() {
    }

    public record Info(String sha256, Instant notAfter) {
    }

    public static Info inspect(byte[] pkcs12, String password, Instant now) {
        if (pkcs12 == null || pkcs12.length == 0) {
            throw new BusinessRuleException("pix_account.certificate_invalid", "Certificado vazio");
        }
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(pkcs12), password == null ? new char[0] : password.toCharArray());
            for (String alias : Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias) && store.getCertificate(alias) instanceof X509Certificate certificate) {
                    if (certificate.getNotAfter().toInstant().isBefore(now)) {
                        throw new BusinessRuleException("pix_account.certificate_expired", "Certificado vencido");
                    }
                    String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(certificate.getEncoded()));
                    return new Info(sha256, certificate.getNotAfter().toInstant());
                }
            }
            throw new BusinessRuleException("pix_account.certificate_invalid",
                    "O arquivo não contém chave privada com certificado");
        } catch (IOException | GeneralSecurityException e) {
            throw new BusinessRuleException("pix_account.certificate_invalid",
                    "Certificado PKCS#12 inválido ou senha incorreta");
        }
    }
}
