package br.com.gestorcobranca.shared.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param issuer         emissor ({@code iss}) exigido na validação
 * @param privateKey     chave privada RSA (PEM PKCS#8) — {@code file:/etc/gestor-cobranca/jwt-private.pem} em produção
 * @param publicKey      chave pública RSA (PEM X.509)
 * @param accessTokenTtl validade do token de acesso (curta: sessão é revalidada no refresh)
 * @param mfaTokenTtl    validade do token intermediário de MFA
 */
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotNull Resource privateKey,
        @NotNull Resource publicKey,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration mfaTokenTtl) {
}
