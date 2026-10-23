package br.com.gestorcobranca.payment.domain.model;

/** Implementação da API Pix: padrão BACEN (qualquer PSP) ou simulador local (bloqueado em produção). */
public enum PixProviderType {
    BACEN_API, SANDBOX
}
