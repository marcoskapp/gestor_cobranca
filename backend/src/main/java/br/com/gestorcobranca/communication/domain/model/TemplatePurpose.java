package br.com.gestorcobranca.communication.domain.model;

/**
 * Finalidade do template (classificação do provedor e relatórios). {@link #AUTHENTICATION}: código de acesso ao portal
 * pedido pelo devedor — corpo curto, só as variáveis do código e, no WhatsApp, categoria de autenticação da Meta
 * ({@code ProviderTemplateRules}); é a única finalidade aceita no envio do código (decisão 1084).
 */
public enum TemplatePurpose {
    COLLECTION,
    REMINDER,
    NOTIFICATION,
    AGREEMENT,
    PAYMENT,
    SERVICE,
    AUTHENTICATION
}
