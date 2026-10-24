package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Caso de cobrança (F06): agrega os títulos em cobrança de um devedor numa carteira (e,
 * portanto, num credor). Existe no máximo um caso ativo por (devedor, carteira).
 * <p>
 * Guarda a posição dos títulos ({@link CaseBalance}, cache do módulo debt), a elegibilidade
 * para contato (RB02), a fila em que está, o dono do atendimento e indicadores de trabalho.
 * Ciclo de vida em {@link CaseStatus}.
 */
@Entity
@Table(name = "collection_case", schema = "collection")
public class CollectionCase extends TenantScopedEntity {

    private static final int REASON_MAX = 500;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CaseStatus status;

    /** Estágio a restaurar ao sair de uma retenção. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resume_status", length = 20)
    private CaseStatus resumeStatus;

    @Column(name = "status_reason", length = REASON_MAX)
    private String statusReason;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 20)
    private CloseReason closeReason;

    @Column(name = "open_balance", nullable = false, precision = 19, scale = 2)
    private Money openBalance;

    @Column(name = "eligible_balance", nullable = false, precision = 19, scale = 2)
    private Money eligibleBalance;

    @Column(name = "open_titles", nullable = false)
    private int openTitles;

    @Column(name = "suspended_titles", nullable = false)
    private int suspendedTitles;

    @Column(name = "disputed_titles", nullable = false)
    private int disputedTitles;

    @Column(name = "oldest_due_date")
    private LocalDate oldestDueDate;

    @Column(name = "balance_updated_at", nullable = false)
    private Instant balanceUpdatedAt;

    @Column(name = "eligible", nullable = false)
    private boolean eligible;

    @Column(name = "eligibility_reasons", length = 400)
    private String eligibilityReasons;

    @Column(name = "contactable_channels", length = 100)
    private String contactableChannels;

    @Column(name = "eligibility_evaluated_at")
    private Instant eligibilityEvaluatedAt;

    @Column(name = "queue_id")
    private UUID queueId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(name = "assignee_user_id")
    private UUID assigneeUserId;

    @Column(name = "assignee_team_id")
    private UUID assigneeTeamId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** Próximo instante em que o caso deve ser trabalhado (retorno agendado, vencimento de promessa). */
    @Column(name = "next_action_at")
    private Instant nextActionAt;

    @Column(name = "last_interaction_at")
    private Instant lastInteractionAt;

    @Column(name = "last_effective_contact_at")
    private Instant lastEffectiveContactAt;

    @Column(name = "interaction_count", nullable = false)
    private int interactionCount;

    @Column(name = "effective_contact_count", nullable = false)
    private int effectiveContactCount;

    /**
     * Score de propensão (0–1000) publicado pelo módulo scoring ({@code CaseScoreChanged}); nulo
     * enquanto não calculado. Uso exclusivo de priorização (ordem da fila e critério de faixa).
     */
    @Column(name = "score")
    private Integer score;

    /** Instante do cálculo do score aplicado (descarta entregas fora de ordem). */
    @Column(name = "score_calculated_at")
    private Instant scoreCalculatedAt;

    protected CollectionCase() {
    }

    public CollectionCase(UUID debtorId, UUID creditorId, UUID portfolioId, CaseBalance balance, Instant now) {
        Guard.require(debtorId != null, "case.debtor_required", "Devedor obrigatório");
        Guard.require(creditorId != null, "case.creditor_required", "Credor obrigatório");
        Guard.require(portfolioId != null, "case.portfolio_required", "Carteira obrigatória");
        Guard.require(balance != null && balance.openTitles() > 0, "case.no_open_titles",
                "Caso só é aberto com títulos em aberto");
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.status = CaseStatus.OPEN;
        this.openedAt = now;
        this.ownerType = OwnerType.NONE;
        applyBalance(balance, now);
    }

    // ------------------------------------------------------------------ posição e elegibilidade

    /** @return true se algum valor mudou */
    public boolean updateBalance(CaseBalance balance, Instant now) {
        requireActive();
        boolean changed = !openBalance.equals(balance.openBalance())
                || !eligibleBalance.equals(balance.eligibleBalance()) || openTitles != balance.openTitles()
                || suspendedTitles != balance.suspendedTitles() || disputedTitles != balance.disputedTitles()
                || !Objects.equals(oldestDueDate, balance.oldestDueDate());
        applyBalance(balance, now);
        return changed;
    }

    private void applyBalance(CaseBalance balance, Instant now) {
        this.openBalance = balance.openBalance();
        this.eligibleBalance = balance.eligibleBalance();
        this.openTitles = balance.openTitles();
        this.suspendedTitles = balance.suspendedTitles();
        this.disputedTitles = balance.disputedTitles();
        this.oldestDueDate = balance.oldestDueDate();
        this.balanceUpdatedAt = now;
    }

    /** @return true se a elegibilidade (flag, motivos ou canais) mudou */
    public boolean applyEligibility(Eligibility eligibility, Instant now) {
        String reasons = join(eligibility.reasons());
        String channels = join(eligibility.contactableChannels());
        boolean changed = eligible != eligibility.eligible() || !Objects.equals(eligibilityReasons, reasons)
                || !Objects.equals(contactableChannels, channels);
        this.eligible = eligibility.eligible();
        this.eligibilityReasons = reasons;
        this.contactableChannels = channels;
        this.eligibilityEvaluatedAt = now;
        return changed;
    }

    // ------------------------------------------------------------------ ciclo de vida

    /**
     * Muda o estágio de tratamento. Durante uma retenção, só o estágio a restaurar é alterado.
     *
     * @return true se o status visível mudou
     */
    public boolean moveToStage(CaseStatus stage, String reason) {
        Guard.require(stage != null && stage.isStage(), "case.stage_invalid", "Estágio de tratamento inválido");
        requireActive();
        if (status.isHold()) {
            resumeStatus = stage;
            return false;
        }
        if (status == stage) {
            return false;
        }
        status = stage;
        statusReason = trim(reason);
        return true;
    }

    /**
     * Coloca o caso numa retenção. Uma retenção de prioridade menor ou igual à corrente não
     * altera o status (é refletida apenas na elegibilidade).
     *
     * @return true se o status mudou
     */
    public boolean enterHold(CaseStatus hold, String reason) {
        Guard.require(hold != null && hold.isHold(), "case.hold_invalid", "Retenção inválida");
        requireActive();
        if (status.isHold()) {
            if (status.holdPriority() >= hold.holdPriority()) {
                return false;
            }
        } else {
            resumeStatus = status;
        }
        status = hold;
        statusReason = trim(reason);
        return true;
    }

    /**
     * Encerra a retenção corrente, se for {@code hold}, restaurando o estágio guardado — ou
     * passando a {@code DISPUTED} se ainda houver contestação bloqueante.
     *
     * @return true se o status mudou
     */
    public boolean releaseHold(CaseStatus hold, boolean blockingDisputeOpen, String reason) {
        Guard.require(hold != null && hold.isHold(), "case.hold_invalid", "Retenção inválida");
        requireActive();
        if (status != hold) {
            return false;
        }
        if (blockingDisputeOpen && hold != CaseStatus.DISPUTED) {
            status = CaseStatus.DISPUTED;
        } else {
            status = resumeStatus == null ? CaseStatus.OPEN : resumeStatus;
            resumeStatus = null;
        }
        statusReason = trim(reason);
        return true;
    }

    public void close(CloseReason reason, String note, Instant now) {
        Guard.require(reason != null, "case.close_reason_required", "Motivo do encerramento obrigatório");
        requireActive();
        this.status = CaseStatus.CLOSED;
        this.resumeStatus = null;
        this.closeReason = reason;
        this.statusReason = trim(note);
        this.closedAt = now;
        this.nextActionAt = null;
        this.ownerType = OwnerType.NONE;
        this.assigneeUserId = null;
        this.queueId = null;
        this.eligible = false;
        this.eligibilityReasons = EligibilityReason.CASE_CLOSED.name();
        this.contactableChannels = null;
        this.eligibilityEvaluatedAt = now;
    }

    // ------------------------------------------------------------------ fila e dono do atendimento

    /** Define a fila do caso (roteamento). @return true se mudou */
    public boolean routeTo(UUID queueId) {
        requireActive();
        if (Objects.equals(this.queueId, queueId)) {
            return false;
        }
        this.queueId = queueId;
        if (ownerType == OwnerType.NONE && queueId != null) {
            ownerType = OwnerType.QUEUE;
        } else if (ownerType == OwnerType.QUEUE && queueId == null && assigneeTeamId == null) {
            ownerType = OwnerType.NONE;
        }
        return true;
    }

    public void assignToUser(UUID userId, UUID teamId, Instant now) {
        Guard.require(userId != null, "case.assignee_required", "Operador obrigatório");
        requireActive();
        this.assigneeUserId = userId;
        this.assigneeTeamId = teamId;
        this.ownerType = OwnerType.USER;
        this.assignedAt = now;
    }

    public void assignToTeam(UUID teamId, Instant now) {
        Guard.require(teamId != null, "case.team_required", "Equipe obrigatória");
        requireActive();
        this.assigneeUserId = null;
        this.assigneeTeamId = teamId;
        this.ownerType = OwnerType.QUEUE;
        this.assignedAt = now;
    }

    /** Devolve o caso à fila (ou equipe), sem operador. */
    public void release(Instant now) {
        requireActive();
        this.assigneeUserId = null;
        this.ownerType = queueId != null || assigneeTeamId != null ? OwnerType.QUEUE : OwnerType.NONE;
        this.assignedAt = now;
    }

    public void handToAssistant(Instant now) {
        requireActive();
        this.assigneeUserId = null;
        this.ownerType = OwnerType.ASSISTANT;
        this.assignedAt = now;
    }

    public boolean isAssignedTo(UUID userId) {
        return userId != null && userId.equals(assigneeUserId);
    }

    // ------------------------------------------------------------------ trabalho

    public void registerInteraction(Instant at, boolean effectiveContact) {
        requireActive();
        interactionCount++;
        if (lastInteractionAt == null || at.isAfter(lastInteractionAt)) {
            lastInteractionAt = at;
        }
        if (effectiveContact) {
            effectiveContactCount++;
            if (lastEffectiveContactAt == null || at.isAfter(lastEffectiveContactAt)) {
                lastEffectiveContactAt = at;
            }
        }
    }

    /**
     * Aplica o score de propensão calculado em {@code calculatedAt}. Cálculos mais antigos que o já
     * aplicado (reentrega ou republicação fora de ordem) são ignorados.
     *
     * @return true se o score mudou
     */
    public boolean applyScore(int value, Instant calculatedAt) {
        Guard.require(value >= 0 && value <= 1000, "case.score_invalid", "Score entre 0 e 1000");
        Guard.require(calculatedAt != null, "case.score_calculated_at_required", "Instante do cálculo obrigatório");
        if (scoreCalculatedAt != null && !calculatedAt.isAfter(scoreCalculatedAt)) {
            return false;
        }
        scoreCalculatedAt = calculatedAt;
        if (Objects.equals(score, value)) {
            return false;
        }
        score = value;
        return true;
    }

    /** Define (ou limpa, com nulo) o próximo instante de trabalho do caso. */
    public void scheduleNextAction(Instant at) {
        this.nextActionAt = at;
    }

    public long daysOverdue(LocalDate today) {
        return balance().daysOverdue(today);
    }

    public CaseBalance balance() {
        return new CaseBalance(openBalance, eligibleBalance, openTitles, suspendedTitles, disputedTitles, oldestDueDate);
    }

    public Eligibility eligibility() {
        Set<EligibilityReason> reasons = split(eligibilityReasons, EligibilityReason.class);
        return new Eligibility(eligible, reasons, split(contactableChannels, InteractionChannel.class));
    }

    private void requireActive() {
        Guard.requireState(status.isActive(), "case.closed", "Caso encerrado");
    }

    private static String trim(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        return t.length() > REASON_MAX ? t.substring(0, REASON_MAX) : t;
    }

    private static String join(Set<? extends Enum<?>> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static <E extends Enum<E>> Set<E> split(String text, Class<E> type) {
        EnumSet<E> result = EnumSet.noneOf(type);
        if (text != null && !text.isBlank()) {
            Arrays.stream(text.split(",")).map(s -> Enum.valueOf(type, s.trim())).forEach(result::add);
        }
        return result;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public CaseStatus getResumeStatus() {
        return resumeStatus;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public CloseReason getCloseReason() {
        return closeReason;
    }

    public Money getOpenBalance() {
        return openBalance;
    }

    public Money getEligibleBalance() {
        return eligibleBalance;
    }

    /** Saldo elegível atribuível (zero quando o caso não é elegível para contato). */
    public Money assignableBalance() {
        return eligible ? eligibleBalance : Money.ZERO;
    }

    public int getDisputedTitles() {
        return disputedTitles;
    }

    public String getContactableChannels() {
        return contactableChannels;
    }

    public int getOpenTitles() {
        return openTitles;
    }

    public int getSuspendedTitles() {
        return suspendedTitles;
    }

    public LocalDate getOldestDueDate() {
        return oldestDueDate;
    }

    public boolean isEligible() {
        return eligible;
    }

    public Instant getEligibilityEvaluatedAt() {
        return eligibilityEvaluatedAt;
    }

    public UUID getQueueId() {
        return queueId;
    }

    public OwnerType getOwnerType() {
        return ownerType;
    }

    public UUID getAssigneeUserId() {
        return assigneeUserId;
    }

    public UUID getAssigneeTeamId() {
        return assigneeTeamId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getNextActionAt() {
        return nextActionAt;
    }

    public Instant getLastInteractionAt() {
        return lastInteractionAt;
    }

    public Instant getLastEffectiveContactAt() {
        return lastEffectiveContactAt;
    }

    public int getInteractionCount() {
        return interactionCount;
    }

    public int getEffectiveContactCount() {
        return effectiveContactCount;
    }

    public Integer getScore() {
        return score;
    }

    public Instant getScoreCalculatedAt() {
        return scoreCalculatedAt;
    }
}
