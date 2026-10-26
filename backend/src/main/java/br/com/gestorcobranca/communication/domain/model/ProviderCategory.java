package br.com.gestorcobranca.communication.domain.model;

/**
 * Categoria do template no provedor (WhatsApp/Meta). Define o formato aceito pelo provedor no envio
 * e o custo da conversa; nos demais canais não se aplica (nulo).
 */
public enum ProviderCategory {
    /** Avisos e lembretes sobre uma relação existente (cobrança, parcela, protocolo). */
    UTILITY,
    /** Conteúdo promocional; a Meta reclassifica textos com apelo comercial. */
    MARKETING,
    /**
     * Código de acesso (OTP): corpo fixo da Meta com um único parâmetro — o código — e botão
     * "copiar código" cujo parâmetro também é o código; a validade é configuração do template na
     * Meta, não variável.
     */
    AUTHENTICATION
}
