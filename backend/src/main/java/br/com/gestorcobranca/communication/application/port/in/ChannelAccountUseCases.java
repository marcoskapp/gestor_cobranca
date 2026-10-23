package br.com.gestorcobranca.communication.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contas de canal (WhatsApp, e-mail, SMS) por assessoria e credor: provedor, remetente e
 * credenciais criptografadas. Uma conta ativa por canal e credor; sem conta do credor, vale a conta
 * padrão da assessoria (credor nulo). Credenciais nunca são devolvidas — apenas quais chaves estão
 * configuradas.
 * <p>
 * Escopo: conta de credor exige o credor no escopo; conta padrão exige acesso a todos os credores.
 * Toda alteração é auditada (sem o conteúdo das credenciais).
 */
public interface ChannelAccountUseCases {

    AccountView create(CreateAccountCommand command);

    AccountView update(UUID accountId, UpdateAccountCommand command);

    /** Substitui o conjunto de credenciais (rotação); exige as chaves obrigatórias do provedor. */
    AccountView rotateCredentials(UUID accountId, Map<String, String> credentials);

    AccountView activate(UUID accountId);

    AccountView deactivate(UUID accountId);

    AccountView get(UUID accountId);

    /** Contas visíveis no escopo (as padrão só para quem tem todos os credores). */
    List<AccountView> list();

    /**
     * @param channel  WHATSAPP, EMAIL ou SMS
     * @param provider WHATSAPP_CLOUD, SMTP ou SANDBOX (este só com o sandbox habilitado)
     * @param creditorId nulo = conta padrão da assessoria
     * @param senderAddress e-mail do remetente (SMTP) ou id do número no provedor (WhatsApp)
     */
    record CreateAccountCommand(String channel, String provider, UUID creditorId, String name, String senderAddress,
                                String senderName, String replyTo, Map<String, String> credentials) {
    }

    record UpdateAccountCommand(String name, String senderAddress, String senderName, String replyTo) {
    }

    /**
     * @param credentialKeys chaves de credencial configuradas (sem valores)
     * @param webhookPath    caminho do webhook a configurar no provedor (nulo se o provedor não usa)
     */
    record AccountView(UUID id, String channel, String provider, UUID creditorId, String name, String senderAddress,
                       String senderName, String replyTo, Set<String> credentialKeys, Instant credentialsUpdatedAt,
                       String webhookPath, boolean active, Instant createdAt, long version) {
    }
}
