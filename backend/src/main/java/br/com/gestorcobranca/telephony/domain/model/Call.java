package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Chamada telefônica vinculada ao caso (F20). O número discado/recebido fica criptografado
 * ({@code number_encrypted}, AES-256-GCM), com blind index ({@code number_hash}, mesma finalidade
 * {@code contact.phone} do cadastro de contatos — localiza retornos e restrições sem o valor em
 * claro) e máscara para exibição.
 * <p>
 * Durações (segundos inteiros, calculadas no encerramento):
 * <ul>
 *   <li><b>toque</b>: do início da discagem (ou do toque, se informado) até o atendimento ou o fim;</li>
 *   <li><b>conversação</b>: da conexão com o operador até o fim (zero sem operador);</li>
 *   <li><b>pós-atendimento</b>: do fim até a tabulação pelo operador.</li>
 * </ul>
 * Idempotência: {@code (provedor, providerCallId)} é único; eventos repetidos ou fora de ordem não
 * mudam o estado (ver {@link CallStatus#canTransitionTo}).
 */
@Entity
@Table(name = "phone_call", schema = "telephony")
public class Call extends TenantScopedEntity {

    public static final String CONTACT_DENIED = "CONTACT_DENIED";

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private CallDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private CallOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CallStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 20)
    private CallResult result;

    /** Conta que disca; muda uma única vez, antes da discagem, pela contingência ({@link #switchToFallback}). */
    @Column(name = "provider_account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    private ProviderType provider;

    /** Conta principal que falhou quando a chamada foi discada pela contingência. */
    @Column(name = "fallback_from_account_id")
    private UUID fallbackFromAccountId;

    @Column(name = "provider_call_id", length = 200)
    private String providerCallId;

    @Column(name = "creditor_id")
    private UUID creditorId;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "debtor_id")
    private UUID debtorId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "operator_id")
    private UUID operatorId;

    @Column(name = "campaign_id", updatable = false)
    private UUID campaignId;

    @Column(name = "entry_id", updatable = false)
    private UUID entryId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "number_encrypted", length = 500)
    private String number;

    @Column(name = "number_hash", length = 64)
    private String numberHash;

    @Column(name = "number_masked", nullable = false, length = 40)
    private String numberMasked;

    @Column(name = "contact_attempt_id")
    private UUID contactAttemptId;

    @Column(name = "denial_reasons", length = 300)
    private String denialReasons;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "dial_started_at")
    private Instant dialStartedAt;

    @Column(name = "ringing_at")
    private Instant ringingAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "tabulated_at")
    private Instant tabulatedAt;

    @Column(name = "ring_seconds")
    private Integer ringSeconds;

    @Column(name = "talk_seconds")
    private Integer talkSeconds;

    @Column(name = "wrap_up_seconds")
    private Integer wrapUpSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "amd_result", length = 10)
    private AmdResult amdResult;

    @Column(name = "abandoned", nullable = false)
    private boolean abandoned;

    @Enumerated(EnumType.STRING)
    @Column(name = "hangup_side", length = 10)
    private HangupSide hangupSide;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_detail", length = 300)
    private String failureDetail;

    @Column(name = "recording_requested", nullable = false)
    private boolean recordingRequested;

    @Column(name = "recording_announced", nullable = false)
    private boolean recordingAnnounced;

    @Column(name = "tabulation_code", length = 60)
    private String tabulationCode;

    @Column(name = "tabulated_by")
    private UUID tabulatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_state", nullable = false, length = 30)
    private InteractionState interactionState;

    @Column(name = "interaction_id")
    private UUID interactionId;

    protected Call() {
    }

    private Call(CallDirection direction, CallOrigin origin, TelephonyAccount account, Instant now) {
        Guard.require(account != null, "call.account_required", "Conta de telefonia obrigatória");
        this.direction = direction;
        this.origin = origin;
        this.accountId = account.getId();
        this.provider = account.getProvider();
        this.queuedAt = now;
        this.interactionState = InteractionState.NONE;
    }

    /**
     * Chamada de saída (clique para ligar ou discador), registrada em {@code QUEUED} antes da
     * autorização do porteiro e da discagem.
     */
    public static Call outbound(CallOrigin origin, TelephonyAccount account, Target target, UUID operatorId,
                                UUID campaignId, UUID entryId, int attemptNumber, Instant now) {
        Guard.require(origin == CallOrigin.CLICK_TO_CALL || origin == CallOrigin.DIALER, "call.origin_invalid",
                "Origem inválida para chamada de saída");
        Guard.require(target != null && target.debtorId() != null && target.creditorId() != null
                && target.caseId() != null, "call.target_required", "Caso, devedor e credor obrigatórios");
        Guard.require(target.number() != null && !target.number().isBlank(), "call.number_required",
                "Telefone obrigatório");
        Guard.require(origin != CallOrigin.CLICK_TO_CALL || operatorId != null, "call.operator_required",
                "Clique para ligar exige o operador");
        Guard.require(origin != CallOrigin.DIALER || (campaignId != null && entryId != null), "call.campaign_required",
                "Chamada do discador exige campanha e item da lista");
        Call call = new Call(CallDirection.OUTBOUND, origin, account, now);
        call.status = CallStatus.QUEUED;
        call.creditorId = target.creditorId();
        call.portfolioId = target.portfolioId();
        call.debtorId = target.debtorId();
        call.caseId = target.caseId();
        call.contactId = target.contactId();
        call.number = target.number();
        call.numberHash = target.numberHash();
        call.numberMasked = target.numberMasked() == null ? "***" : target.numberMasked();
        call.operatorId = operatorId;
        call.campaignId = campaignId;
        call.entryId = entryId;
        call.attemptNumber = Math.max(1, attemptNumber);
        call.recordingRequested = account.isRecordingEnabled();
        call.recordingAnnounced = account.isRecordingEnabled() && account.isRecordingNotice();
        return call;
    }

    /**
     * Contingência: a conta principal falhou de forma transitória <b>sem</b> criar a chamada no
     * provedor; a mesma chamada (mesmo id, mesma tentativa no compliance) passa a ser discada pela
     * conta de contingência. Uma única vez por chamada e somente antes da discagem — assim nunca há
     * duas chamadas para o mesmo pedido.
     */
    public void switchToFallback(TelephonyAccount fallback) {
        Guard.require(fallback != null && !fallback.getId().equals(accountId), "call.fallback_invalid",
                "Conta de contingência inválida");
        Guard.requireState(status == CallStatus.QUEUED && providerCallId == null, "call.fallback_not_queued",
                "A contingência só vale antes da discagem");
        Guard.requireState(fallbackFromAccountId == null, "call.fallback_already_used",
                "A chamada já foi desviada para a contingência");
        this.fallbackFromAccountId = accountId;
        this.accountId = fallback.getId();
        this.provider = fallback.getProvider();
        this.recordingRequested = fallback.isRecordingEnabled();
        this.recordingAnnounced = fallback.isRecordingEnabled() && fallback.isRecordingNotice();
    }

    /** Chamada recebida notificada pelo provedor (nasce tocando, ainda sem devedor identificado). */
    public static Call inbound(TelephonyAccount account, String providerCallId, String number, String numberHash,
                               String numberMasked, Instant at) {
        Guard.require(providerCallId != null && !providerCallId.isBlank(), "call.provider_id_required",
                "Id do provedor obrigatório");
        Call call = new Call(CallDirection.INBOUND, CallOrigin.INBOUND, account, at);
        call.status = CallStatus.RINGING;
        call.providerCallId = providerCallId;
        call.ringingAt = at;
        call.number = number;
        call.numberHash = numberHash;
        call.numberMasked = numberMasked == null ? "***" : numberMasked;
        call.attemptNumber = 1;
        call.recordingRequested = account.isRecordingEnabled();
        call.recordingAnnounced = account.isRecordingEnabled() && account.isRecordingNotice();
        return call;
    }

    /**
     * Devedor, caso e contato da chamada recebida (identificação pelo número ou pelo operador).
     */
    public void identify(UUID debtorId, UUID creditorId, UUID portfolioId, UUID caseId, UUID contactId) {
        Guard.requireState(direction == CallDirection.INBOUND, "call.not_inbound",
                "Somente chamadas recebidas são identificadas depois");
        Guard.require(debtorId != null && creditorId != null && caseId != null, "call.target_required",
                "Caso, devedor e credor obrigatórios");
        Guard.requireState(this.caseId == null || this.caseId.equals(caseId), "call.already_identified",
                "Chamada já vinculada a outro caso");
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.caseId = caseId;
        this.contactId = contactId;
    }

    /** Porteiro de contato negou antes da discagem: a chamada não chega ao provedor. */
    public void block(String reasons, Instant at) {
        Guard.requireState(status == CallStatus.QUEUED, "call.not_queued", "Chamada já discada");
        this.denialReasons = truncate(reasons, 300);
        this.failureCode = CONTACT_DENIED;
        this.status = CallStatus.FAILED;
        this.result = CallResult.FAILED;
        this.endedAt = at;
        this.ringSeconds = 0;
        this.talkSeconds = 0;
        this.interactionState = InteractionState.SKIPPED;
    }

    /** Provedor aceitou o pedido de discagem. */
    public void dialing(String providerCallId, UUID contactAttemptId, Instant at) {
        Guard.requireState(status == CallStatus.QUEUED, "call.not_queued", "Chamada já discada");
        Guard.require(providerCallId != null && !providerCallId.isBlank(), "call.provider_id_required",
                "Id do provedor obrigatório");
        this.providerCallId = providerCallId;
        this.contactAttemptId = contactAttemptId;
        this.dialStartedAt = at;
        this.status = CallStatus.DIALING;
    }

    /** Falha antes de chegar ao provedor (recusa do pedido, conta indisponível, tentativas esgotadas). */
    public void failBeforeDialing(String code, String detail, Instant at) {
        Guard.requireState(status == CallStatus.QUEUED, "call.not_queued", "Chamada já discada");
        this.failureCode = truncate(code, 60);
        this.failureDetail = truncate(detail, 300);
        terminate(CallStatus.FAILED, at, HangupSide.PROVIDER);
    }

    /** @return false se o evento é obsoleto ou repetido */
    public boolean ringing(Instant at) {
        if (!status.canTransitionTo(CallStatus.RINGING)) {
            return false;
        }
        this.ringingAt = at;
        this.status = CallStatus.RINGING;
        return true;
    }

    /** Atendimento. No clique para ligar o operador já está na linha: a conexão é imediata. */
    public boolean answered(Instant at, AmdResult amd) {
        if (!status.canTransitionTo(CallStatus.ANSWERED)) {
            return false;
        }
        this.answeredAt = at;
        this.amdResult = amd;
        this.status = CallStatus.ANSWERED;
        if (origin == CallOrigin.CLICK_TO_CALL) {
            this.connectedAt = at;
        }
        return true;
    }

    /** Chamada atendida conectada ao operador livre (discador ou recebida). */
    public void connect(UUID operatorId, Instant at) {
        Guard.requireState(status == CallStatus.ANSWERED || (status == CallStatus.RINGING && direction == CallDirection.INBOUND),
                "call.not_answered", "Chamada não está atendida");
        Guard.require(operatorId != null, "call.operator_required", "Operador obrigatório");
        Guard.requireState(connectedAt == null, "call.already_connected", "Chamada já conectada a um operador");
        if (status == CallStatus.RINGING) {
            this.answeredAt = at;
            this.status = CallStatus.ANSWERED;
        }
        this.operatorId = operatorId;
        this.connectedAt = at;
    }

    /** Atendida por pessoa sem operador livre: o sistema encerra (abandono). */
    public void markAbandoned() {
        Guard.requireState(status == CallStatus.ANSWERED && connectedAt == null, "call.not_abandonable",
                "Somente chamada atendida sem operador é abandonada");
        this.abandoned = true;
    }

    /**
     * Encerramento informado pelo provedor ou pedido pelo operador/sistema.
     *
     * @param target COMPLETED (após atendimento), NO_ANSWER, BUSY, FAILED ou CANCELLED
     * @return false se obsoleto/repetido
     */
    public boolean end(CallStatus target, Instant at, HangupSide side, String cause) {
        Guard.require(target != null && target.isTerminal(), "call.end_status_invalid", "Estado final inválido");
        CallStatus effective = target;
        if (status == CallStatus.ANSWERED && target != CallStatus.COMPLETED) {
            // falha/cancelamento depois do atendimento: a chamada foi atendida e terminou
            effective = CallStatus.COMPLETED;
        }
        if (!status.canTransitionTo(effective)) {
            return false;
        }
        if (cause != null && effective != CallStatus.COMPLETED) {
            this.failureCode = truncate(cause, 60);
        }
        terminate(effective, at, side);
        return true;
    }

    private void terminate(CallStatus terminal, Instant at, HangupSide side) {
        Instant end = at.isBefore(queuedAt) ? queuedAt : at;
        this.status = terminal;
        this.endedAt = end;
        this.hangupSide = side;
        Instant ringStart = ringingAt != null ? ringingAt : dialStartedAt;
        Instant ringEnd = answeredAt != null ? answeredAt : end;
        this.ringSeconds = ringStart == null ? 0 : seconds(ringStart, ringEnd);
        this.talkSeconds = connectedAt == null ? 0 : seconds(connectedAt, end);
        this.result = resultOf(terminal);
        this.interactionState = initialInteractionState();
    }

    private CallResult resultOf(CallStatus terminal) {
        return switch (terminal) {
            case COMPLETED -> {
                if (amdResult == AmdResult.MACHINE) {
                    yield CallResult.VOICEMAIL;
                }
                // atendida por pessoa sem chegar a um operador conta como abandono (critério conservador)
                yield connectedAt != null && !abandoned ? CallResult.ANSWERED : CallResult.ABANDONED;
            }
            case NO_ANSWER -> CallResult.NO_ANSWER;
            case BUSY -> CallResult.BUSY;
            case FAILED -> CallResult.FAILED;
            case CANCELLED -> CallResult.CANCELLED;
            default -> throw new IllegalStateException("Estado não terminal: " + terminal);
        };
    }

    private InteractionState initialInteractionState() {
        if (!wasDialed()) {
            return InteractionState.SKIPPED;
        }
        if (operatorId != null) {
            return InteractionState.AWAITING_TABULATION;
        }
        return caseId == null ? InteractionState.SKIPPED : InteractionState.PENDING;
    }

    /** Chegou ao provedor (discada ou recebida): tem tentativa, resultado publicado e interação. */
    public boolean wasDialed() {
        return dialStartedAt != null || direction == CallDirection.INBOUND;
    }

    /**
     * Tabulação do operador no pós-atendimento (ou automática no prazo). Define o pós-atendimento
     * e libera o registro da interação no caso.
     */
    public void tabulate(String code, UUID tabulatedBy, Instant at) {
        Guard.requireState(status.isTerminal(), "call.in_progress", "A chamada ainda está em andamento");
        Guard.requireState(interactionState == InteractionState.AWAITING_TABULATION, "call.already_tabulated",
                "Chamada já tabulada");
        Guard.require(code != null && !code.isBlank() && code.length() <= 60, "call.tabulation_required",
                "Tabulação obrigatória");
        Guard.requireState(caseId != null, "call.case_required",
                "Vincule a chamada a um caso antes de tabular");
        this.tabulationCode = code;
        this.tabulatedBy = tabulatedBy;
        this.tabulatedAt = at.isBefore(endedAt) ? endedAt : at;
        this.wrapUpSeconds = seconds(endedAt, tabulatedAt);
        this.interactionState = InteractionState.PENDING;
    }

    /** Tabulação automática (sem operador ou prazo do pós-atendimento vencido). */
    public void autoTabulate(String code, Instant at) {
        Guard.requireState(status.isTerminal(), "call.in_progress", "A chamada ainda está em andamento");
        Guard.requireState(interactionState == InteractionState.AWAITING_TABULATION
                || interactionState == InteractionState.PENDING, "call.already_tabulated", "Chamada já tabulada");
        if (interactionState == InteractionState.AWAITING_TABULATION) {
            this.tabulatedAt = at.isBefore(endedAt) ? endedAt : at;
            this.wrapUpSeconds = seconds(endedAt, tabulatedAt);
        }
        this.tabulationCode = code;
        this.interactionState = caseId == null ? InteractionState.SKIPPED : InteractionState.PENDING;
    }

    public void interactionRecorded(UUID interactionId) {
        this.interactionId = interactionId;
        this.interactionState = InteractionState.RECORDED;
    }

    public void interactionSkipped() {
        this.interactionState = InteractionState.SKIPPED;
    }

    /** LGPD: devedor anonimizado — número e índice eliminados. */
    public void anonymizeNumber() {
        this.number = null;
        this.numberHash = null;
        this.numberMasked = "***";
    }

    private static int seconds(Instant from, Instant to) {
        return (int) Math.max(0, Duration.between(from, to).toSeconds());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Destino da chamada de saída. O número em claro só circula em memória para a discagem.
     *
     * @param numberHash   blind index ({@code contact.phone})
     * @param numberMasked número mascarado
     */
    public record Target(UUID creditorId, UUID portfolioId, UUID debtorId, UUID caseId, UUID contactId, String number,
                         String numberHash, String numberMasked) {
    }

    public CallDirection getDirection() {
        return direction;
    }

    public CallOrigin getOrigin() {
        return origin;
    }

    public CallStatus getStatus() {
        return status;
    }

    public CallResult getResult() {
        return result;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public ProviderType getProvider() {
        return provider;
    }

    public UUID getFallbackFromAccountId() {
        return fallbackFromAccountId;
    }

    public String getProviderCallId() {
        return providerCallId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    /** Número em claro: uso exclusivo da discagem; nunca registrar em log nem devolver na API. */
    public String getNumber() {
        return number;
    }

    public String getNumberHash() {
        return numberHash;
    }

    public String getNumberMasked() {
        return numberMasked;
    }

    public UUID getContactAttemptId() {
        return contactAttemptId;
    }

    public String getDenialReasons() {
        return denialReasons;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getDialStartedAt() {
        return dialStartedAt;
    }

    public Instant getRingingAt() {
        return ringingAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getTabulatedAt() {
        return tabulatedAt;
    }

    public Integer getRingSeconds() {
        return ringSeconds;
    }

    public Integer getTalkSeconds() {
        return talkSeconds;
    }

    public Integer getWrapUpSeconds() {
        return wrapUpSeconds;
    }

    public AmdResult getAmdResult() {
        return amdResult;
    }

    public boolean isAbandoned() {
        return abandoned;
    }

    public HangupSide getHangupSide() {
        return hangupSide;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public boolean isRecordingRequested() {
        return recordingRequested;
    }

    public boolean isRecordingAnnounced() {
        return recordingAnnounced;
    }

    public String getTabulationCode() {
        return tabulationCode;
    }

    public UUID getTabulatedBy() {
        return tabulatedBy;
    }

    public InteractionState getInteractionState() {
        return interactionState;
    }

    public UUID getInteractionId() {
        return interactionId;
    }
}
