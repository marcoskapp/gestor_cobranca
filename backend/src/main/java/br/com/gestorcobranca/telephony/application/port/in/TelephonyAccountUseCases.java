package br.com.gestorcobranca.telephony.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contas de telefonia da assessoria: várias contas ativas, de provedores diferentes (ambiente
 * multiprovedor); qual conta disca é decidido pelo roteamento ({@link TelephonyRoutingUseCases}).
 * {@code creditorId} restringe a conta a um credor (bina do credor). Credenciais nunca são
 * devolvidas (somente as chaves configuradas) nem gravadas na auditoria. O provedor simulado
 * ({@code SANDBOX}) só existe em ambiente local/teste; provedor sem adaptador
 * ({@code NOT_IMPLEMENTED}) não aceita conta; com contrato a confirmar ({@code CONTRACT_PENDING}) a
 * conta nasce inativa e só é ativada com a homologação ligada para ele.
 */
public interface TelephonyAccountUseCases {

    AccountView create(CreateAccountCommand command);

    AccountView update(UUID accountId, UpdateAccountCommand command);

    AccountView rotateCredentials(UUID accountId, Map<String, String> credentials);

    AccountView activate(UUID accountId);

    AccountView deactivate(UUID accountId);

    AccountView get(UUID accountId);

    List<AccountView> list();

    /** Provedores conhecidos, credenciais exigidas e situação do adaptador neste ambiente. */
    List<ProviderView> providers();

    /**
     * Comparação entre contas/provedores no período (dias de operação, inclusive): volume, taxa de
     * atendimento (ASR), conexão, falhas, contingência, tempos médios e custo estimado pela tarifa
     * informada na conta.
     */
    List<AccountComparison> comparison(LocalDate from, LocalDate to);

    /**
     * @param provider         {@code SANDBOX}, {@code ALVARIA} ou {@code TARIFANDO}
     * @param creditorId       nulo = conta da assessoria; preenchido = restrita ao credor
     * @param callerId         número de origem (E.164)
     * @param recordingEnabled grava as chamadas (exige {@code recordingNotice})
     * @param recordingNotice  toca o aviso de gravação ao devedor no início da chamada
     * @param ratePerMinute    tarifa por minuto (R$), opcional, só para comparar custo estimado
     */
    record CreateAccountCommand(String provider, UUID creditorId, String name, String callerId,
                                boolean recordingEnabled, boolean recordingNotice, Map<String, String> credentials,
                                BigDecimal ratePerMinute) {

        public CreateAccountCommand(String provider, UUID creditorId, String name, String callerId,
                                    boolean recordingEnabled, boolean recordingNotice, Map<String, String> credentials) {
            this(provider, creditorId, name, callerId, recordingEnabled, recordingNotice, credentials, null);
        }
    }

    record UpdateAccountCommand(String name, String callerId, boolean recordingEnabled, boolean recordingNotice,
                                BigDecimal ratePerMinute) {
    }

    /**
     * @param webhookPath caminho do webhook a cadastrar no provedor
     * @param readiness   {@code READY}, {@code CONTRACT_PENDING} ou {@code NOT_IMPLEMENTED}
     * @param dialable    o provedor disca neste ambiente (pronto, ou contrato pendente em homologação)
     */
    record AccountView(UUID id, String provider, UUID creditorId, String name, String callerId,
                       boolean recordingEnabled, boolean recordingNotice, Set<String> credentialKeys,
                       Instant credentialsUpdatedAt, String webhookPath, boolean active, Instant createdAt,
                       long version, String providerLabel, String readiness, boolean dialable,
                       BigDecimal ratePerMinute) {
    }

    record ProviderView(String provider, String label, Set<String> requiredCredentials, String readiness,
                        boolean dialable, boolean available) {
    }

    /**
     * @param asr              atendidas ÷ discadas (0–1; nulo sem discagem)
     * @param connectRate      conectadas a operador ÷ atendidas
     * @param failureRate      (recusas + falhas) ÷ (discadas + recusas)
     * @param billableMinutes  minutos do atendimento ao fim (base da tarifa)
     * @param estimatedCost    minutos × tarifa da conta (nulo sem tarifa)
     */
    record AccountComparison(UUID accountId, String provider, String name, boolean active, long dialed,
                             long rejected, long answered, long connected, long failed, long fallbackIn,
                             Double asr, Double connectRate, Double failureRate, Double averageRingSeconds,
                             Double averageTalkSeconds, BigDecimal billableMinutes, BigDecimal ratePerMinute,
                             BigDecimal estimatedCost) {
    }
}
