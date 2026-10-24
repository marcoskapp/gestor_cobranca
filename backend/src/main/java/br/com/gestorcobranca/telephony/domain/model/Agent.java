package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Operador habilitado na telefonia: ramal/usuário SIP configurado pelo gestor e estado de presença
 * para o discador (ACD). O operador escolhe apenas disponível, pausa (com motivo) e saída;
 * {@code ON_CALL} e {@code WRAP_UP} são conduzidos pelas chamadas. Cada mudança encerra um
 * intervalo de presença ({@link PresenceChange}) gravado de forma append-only.
 * <p>
 * Distribuição: entre os disponíveis da campanha, recebe a chamada quem está livre há mais tempo
 * ({@code lastAvailableAt}). Sem sinal do cliente ({@code heartbeat}) por mais que o limite, o
 * operador sai automaticamente (não recebe chamadas de um navegador fechado).
 */
@Entity
@Table(name = "agent", schema = "telephony")
public class Agent extends TenantScopedEntity {

    private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9_.@:+\\-]{2,64}");

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "extension", nullable = false, length = 64)
    private String extension;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PresenceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 30)
    private PauseReason pauseReason;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "current_call_id")
    private UUID currentCallId;

    @Column(name = "status_since", nullable = false)
    private Instant statusSince;

    @Column(name = "last_available_at")
    private Instant lastAvailableAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "logged_in_at")
    private Instant loggedInAt;

    protected Agent() {
    }

    public Agent(UUID userId, String extension, Instant now) {
        Guard.require(userId != null, "agent.user_required", "Usuário obrigatório");
        this.userId = userId;
        this.active = true;
        this.status = PresenceStatus.OFFLINE;
        this.statusSince = now;
        changeExtension(extension);
    }

    public void changeExtension(String extension) {
        Guard.require(extension != null && EXTENSION.matcher(extension.trim()).matches(), "agent.extension_invalid",
                "Ramal inválido (2 a 64 caracteres: letras, dígitos e . _ - @ : +)");
        this.extension = extension.trim();
    }

    public Optional<PresenceChange> deactivate(Instant now) {
        Guard.requireState(status != PresenceStatus.ON_CALL, "agent.on_call", "Operador em chamada");
        this.active = false;
        return status == PresenceStatus.OFFLINE ? Optional.empty() : Optional.of(move(PresenceStatus.OFFLINE, null, now));
    }

    public void activate() {
        this.active = true;
    }

    /** Entrada na telefonia, opcionalmente numa campanha de discagem. */
    public PresenceChange login(UUID campaignId, Instant now) {
        Guard.requireState(active, "agent.inactive", "Operador desabilitado na telefonia");
        Guard.requireState(status == PresenceStatus.OFFLINE, "agent.already_logged_in", "Operador já conectado");
        this.campaignId = campaignId;
        this.loggedInAt = now;
        this.lastHeartbeatAt = now;
        return move(PresenceStatus.AVAILABLE, null, now);
    }

    /** Fim da pausa. */
    public PresenceChange available(Instant now) {
        Guard.requireState(status == PresenceStatus.PAUSED, "agent.not_paused",
                status == PresenceStatus.WRAP_UP ? "Tabule a chamada para ficar disponível" : "Operador não está em pausa");
        return move(PresenceStatus.AVAILABLE, null, now);
    }

    public PresenceChange pause(PauseReason reason, Instant now) {
        Guard.require(reason != null, "agent.pause_reason_required", "Motivo da pausa obrigatório");
        Guard.requireState(status == PresenceStatus.AVAILABLE || status == PresenceStatus.PAUSED, "agent.cannot_pause",
                status == PresenceStatus.WRAP_UP ? "Tabule a chamada antes de pausar" : "Operador não pode pausar agora");
        return move(PresenceStatus.PAUSED, reason, now);
    }

    /** Entra ou sai (nulo) da campanha de discagem; vale para as próximas chamadas. */
    public void joinCampaign(UUID campaignId) {
        Guard.requireState(status.isLoggedIn(), "agent.offline", "Operador não está conectado");
        Guard.requireState(active, "agent.inactive", "Operador desabilitado na telefonia");
        this.campaignId = campaignId;
    }

    /** Reserva o operador para uma chamada (clique para ligar ou distribuição do discador). */
    public PresenceChange reserve(UUID callId, Instant now) {
        Guard.requireState(active, "agent.inactive", "Operador desabilitado na telefonia");
        Guard.requireState(status == PresenceStatus.AVAILABLE, "agent.not_available",
                status == PresenceStatus.WRAP_UP ? "Tabule a chamada anterior antes de ligar"
                        : "Operador precisa estar disponível na telefonia");
        this.currentCallId = callId;
        return move(PresenceStatus.ON_CALL, null, now);
    }

    /**
     * Fim da chamada do operador: pós-atendimento (aguardando tabulação) ou, quando não há o que
     * tabular, de volta a disponível.
     *
     * @return vazio se o operador não está nesta chamada (evento atrasado)
     */
    public Optional<PresenceChange> callEnded(UUID callId, boolean awaitingTabulation, Instant now) {
        if (status != PresenceStatus.ON_CALL || !callId.equals(currentCallId)) {
            return Optional.empty();
        }
        if (awaitingTabulation) {
            return Optional.of(move(PresenceStatus.WRAP_UP, null, now));
        }
        this.currentCallId = null;
        return Optional.of(move(PresenceStatus.AVAILABLE, null, now));
    }

    /** Tabulação concluída: fim do pós-atendimento. */
    public Optional<PresenceChange> wrapUpDone(UUID callId, Instant now) {
        if (status != PresenceStatus.WRAP_UP || !callId.equals(currentCallId)) {
            return Optional.empty();
        }
        this.currentCallId = null;
        return Optional.of(move(PresenceStatus.AVAILABLE, null, now));
    }

    /**
     * Saída. Em chamada só é forçada (supervisor ou queda de sinal); o pós-atendimento pendente
     * fica para a tabulação automática.
     */
    public Optional<PresenceChange> logout(boolean force, Instant now) {
        if (status == PresenceStatus.OFFLINE) {
            return Optional.empty();
        }
        Guard.requireState(force || status != PresenceStatus.ON_CALL, "agent.on_call",
                "Encerre a chamada antes de sair");
        PresenceChange change = move(PresenceStatus.OFFLINE, null, now);
        this.campaignId = null;
        this.currentCallId = null;
        return Optional.of(change);
    }

    public void heartbeat(Instant now) {
        Guard.requireState(status.isLoggedIn(), "agent.offline", "Operador não está conectado");
        this.lastHeartbeatAt = now;
    }

    /** Sem sinal do cliente além do limite (considera também a entrada). */
    public boolean heartbeatExpired(Instant now, Duration timeout) {
        if (!status.isLoggedIn()) {
            return false;
        }
        Instant last = lastHeartbeatAt == null ? statusSince : lastHeartbeatAt;
        return last.plus(timeout).isBefore(now);
    }

    private PresenceChange move(PresenceStatus target, PauseReason reason, Instant now) {
        Instant at = now.isBefore(statusSince) ? statusSince : now;
        PresenceChange change = new PresenceChange(getId(), userId, status, pauseReason, campaignId, statusSince, target,
                reason, at);
        this.status = target;
        this.pauseReason = reason;
        this.statusSince = at;
        if (target == PresenceStatus.AVAILABLE) {
            this.lastAvailableAt = at;
        }
        return change;
    }

    /**
     * Mudança de presença: o intervalo anterior ({@code from*}, de {@code fromSince} a {@code at})
     * e o novo estado.
     */
    public record PresenceChange(UUID agentId, UUID userId, PresenceStatus from, PauseReason fromReason,
                                 UUID campaignId, Instant fromSince, PresenceStatus to, PauseReason toReason,
                                 Instant at) {

        public long durationSeconds() {
            return Math.max(0, Duration.between(fromSince, at).toSeconds());
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public String getExtension() {
        return extension;
    }

    public boolean isActive() {
        return active;
    }

    public PresenceStatus getStatus() {
        return status;
    }

    public PauseReason getPauseReason() {
        return pauseReason;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getCurrentCallId() {
        return currentCallId;
    }

    public Instant getStatusSince() {
        return statusSince;
    }

    public Instant getLastAvailableAt() {
        return lastAvailableAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public Instant getLoggedInAt() {
        return loggedInAt;
    }
}
