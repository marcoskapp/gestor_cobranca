package br.com.gestorcobranca.communication.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Provedor que atende uma conta de canal. O SMS ainda não tem provedor de produção (issue #12):
 * apenas a porta está definida, e contas SMS só podem usar o sandbox (ambiente local/teste).
 */
public enum ProviderType {
    /** WhatsApp Cloud API (Meta). Credenciais: {@code accessToken}, {@code appSecret}, {@code verifyToken}. */
    WHATSAPP_CLOUD(EnumSet.of(ChannelType.WHATSAPP), Set.of("accessToken", "appSecret", "verifyToken"), true),
    /** SMTP da plataforma (Spring Mail); a conta define remetente e resposta. Sem credenciais por tenant. */
    SMTP(EnumSet.of(ChannelType.EMAIL), Set.of(), false),
    /**
     * Simulador para ambiente local e testes: aceita o envio e simula entregas e respostas. Só
     * disponível com {@code app.communication.sandbox.enabled=true}, proibido no perfil {@code prod}.
     */
    SANDBOX(EnumSet.allOf(ChannelType.class), Set.of(), true);

    private final Set<ChannelType> channels;
    private final Set<String> requiredCredentials;
    private final boolean webhook;

    ProviderType(Set<ChannelType> channels, Set<String> requiredCredentials, boolean webhook) {
        this.channels = channels;
        this.requiredCredentials = requiredCredentials;
        this.webhook = webhook;
    }

    public boolean supports(ChannelType channel) {
        return channels.contains(channel);
    }

    /** Chaves obrigatórias do JSON de credenciais. */
    public Set<String> requiredCredentials() {
        return requiredCredentials;
    }

    /** Recebe notificações do provedor por webhook ({@code /api/v1/webhooks/<provedor>/{routingKey}}). */
    public boolean hasWebhook() {
        return webhook;
    }
}
