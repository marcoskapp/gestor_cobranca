package br.com.gestorcobranca.identity.application.port.out;

import java.time.Instant;

/** Envio de e-mails transacionais de identidade (ativação, redefinição, alertas de segurança). */
public interface IdentityMailer {

    void sendActivation(String to, String name, String tenantName, String link, Instant expiresAt);

    void sendPasswordReset(String to, String name, String link, Instant expiresAt);

    /** Alerta de segurança: conta bloqueada, MFA redefinido, reuso de token detectado. */
    void sendSecurityAlert(String to, String name, String subject, String message);
}
