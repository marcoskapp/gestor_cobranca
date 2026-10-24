package br.com.gestorcobranca.telephony.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contas do provedor de telefonia por assessoria (conta padrão) ou por credor (número de origem
 * próprio). Credenciais nunca são devolvidas (somente as chaves configuradas) nem gravadas na
 * auditoria. O provedor simulado ({@code SANDBOX}) só existe em ambiente local/teste.
 */
public interface TelephonyAccountUseCases {

    AccountView create(CreateAccountCommand command);

    AccountView update(UUID accountId, UpdateAccountCommand command);

    AccountView rotateCredentials(UUID accountId, Map<String, String> credentials);

    AccountView activate(UUID accountId);

    AccountView deactivate(UUID accountId);

    AccountView get(UUID accountId);

    List<AccountView> list();

    /**
     * @param provider         provedor ({@code SANDBOX} enquanto a issue #12 não define o real)
     * @param creditorId       nulo = conta padrão da assessoria
     * @param callerId         número de origem (E.164)
     * @param recordingEnabled grava as chamadas (exige {@code recordingNotice})
     * @param recordingNotice  toca o aviso de gravação ao devedor no início da chamada
     */
    record CreateAccountCommand(String provider, UUID creditorId, String name, String callerId,
                                boolean recordingEnabled, boolean recordingNotice, Map<String, String> credentials) {
    }

    record UpdateAccountCommand(String name, String callerId, boolean recordingEnabled, boolean recordingNotice) {
    }

    /** @param webhookPath caminho do webhook a cadastrar no provedor */
    record AccountView(UUID id, String provider, UUID creditorId, String name, String callerId,
                       boolean recordingEnabled, boolean recordingNotice, Set<String> credentialKeys,
                       Instant credentialsUpdatedAt, String webhookPath, boolean active, Instant createdAt,
                       long version) {
    }
}
