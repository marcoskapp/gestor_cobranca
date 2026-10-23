package br.com.gestorcobranca.payment.domain.model;

/** Autenticação das notificações do PSP: mTLS (certificado do PSP verificado no ALB e fixado na conta) ou HMAC-SHA256 do corpo. */
public enum WebhookAuthMode {
    MTLS, HMAC
}
