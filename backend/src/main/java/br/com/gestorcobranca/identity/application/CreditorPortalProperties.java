package br.com.gestorcobranca.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Parâmetros da identidade da pessoa do credor no portal do credor ({@code app.security.creditor-portal.*}, ADR 0011).
 * Senha (tamanho, histórico, validade) e bloqueio por conta seguem {@code app.security.password} e
 * {@code app.security.lockout}, como o usuário interno.
 *
 * @param idleTimeout       inatividade máxima da sessão
 * @param sessionLifetime   duração máxima da sessão (expiração absoluta)
 * @param maxSessions       sessões simultâneas por pessoa; a mais antiga é revogada ao exceder
 * @param invitationTtl     validade do link de convite
 * @param passwordResetTtl  validade do link de redefinição de senha
 * @param ip                limite persistido por IP no login (entre instâncias)
 */
@ConfigurationProperties(prefix = "app.security.creditor-portal")
public record CreditorPortalProperties(
        @DefaultValue("PT30M") Duration idleTimeout,
        @DefaultValue("PT8H") Duration sessionLifetime,
        @DefaultValue("3") int maxSessions,
        @DefaultValue("PT72H") Duration invitationTtl,
        @DefaultValue("PT1H") Duration passwordResetTtl,
        @DefaultValue Ip ip) {

    /**
     * @param maxFailures falhas (senha, código, link) na janela que bloqueiam o IP
     * @param maxRequests tentativas na janela (com ou sem falha) que bloqueiam o IP
     */
    public record Ip(@DefaultValue("20") int maxFailures, @DefaultValue("60") int maxRequests,
                     @DefaultValue("PT15M") Duration window, @DefaultValue("PT5M") Duration baseLock,
                     @DefaultValue("PT1H") Duration maxLock) {
    }
}
