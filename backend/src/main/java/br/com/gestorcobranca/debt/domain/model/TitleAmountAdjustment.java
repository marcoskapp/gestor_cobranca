package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Correção manual do valor do título pela assessoria: motivo (lista + texto), componentes afetados com o valor anterior
 * e o novo, vínculo opcional com a contestação e a evidência, quem pediu e quem decidiu. Histórico append-only: os
 * dados do pedido nunca mudam e o desfecho (aplicado, recusado, retirado) é gravado uma única vez (trigger no banco).
 * <p>
 * Regra de autorização (RB06): redução até o limite da assessoria é aplicada no registro; redução acima do limite e
 * qualquer aumento do saldo ficam aguardando outra pessoa com {@code TITLE_AMOUNT_APPROVE}, com evidência (arquivo ou
 * contestação). O limite é também <b>acumulado por título</b> numa janela: as reduções já aplicadas sem segunda pessoa
 * no mesmo título contam junto com a atual (decisão do responsável sobre a 864). Quem pede nunca aprova o próprio
 * pedido.
 */
@Entity
@Table(name = "title_amount_adjustment", schema = "debt")
public class TitleAmountAdjustment extends TenantScopedEntity {

    public static final int NOTE_MAX = 2000;
    public static final int NAME_MAX = 200;

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AmountAdjustmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", nullable = false, length = 20, updatable = false)
    private AmountAdjustmentMode approvalMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30, updatable = false)
    private AmountAdjustmentReason reasonCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason_note_encrypted", nullable = false, length = 24000, updatable = false)
    private String reasonNote;

    @Column(name = "dispute_id", updatable = false)
    private UUID disputeId;

    @Column(name = "evidence_key", length = 500, updatable = false)
    private String evidenceKey;

    @Column(name = "evidence_name", length = 255, updatable = false)
    private String evidenceName;

    @Column(name = "evidence_content_type", length = 100, updatable = false)
    private String evidenceContentType;

    @Column(name = "evidence_size", updatable = false)
    private Long evidenceSize;

    @Column(name = "evidence_sha256", length = 64, updatable = false)
    private String evidenceSha256;

    @Column(name = "previous_principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money previousPrincipal;

    @Column(name = "new_principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money newPrincipal;

    @Column(name = "previous_open_balance", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money previousOpenBalance;

    @Column(name = "new_open_balance", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money newOpenBalance;

    @Column(name = "delta", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money delta;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_by_name", length = NAME_MAX, updatable = false)
    private String requestedByName;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_by_name", length = NAME_MAX)
    private String decidedByName;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "decision_note_encrypted", length = 24000)
    private String decisionNote;

    @Column(name = "applied_at")
    private Instant appliedAt;

    /** Limite da assessoria no pedido (nulo em pedido anterior à gravação: vale o limite atual). */
    @Column(name = "request_threshold", precision = 19, scale = 2, updatable = false)
    private Money requestThreshold;

    /** Reduções aplicadas sem segunda pessoa no título na janela, no pedido (nulo em pedido anterior à gravação). */
    @Column(name = "request_recent_reductions", precision = 19, scale = 2, updatable = false)
    private Money requestRecentReductions;

    /** Só no pedido: a redução cabia sozinha no limite, mas não somada às recentes do título (não persistido). */
    @Transient
    private boolean cumulativeLimitReached;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "title_amount_adjustment_line", schema = "debt",
            joinColumns = @JoinColumn(name = "adjustment_id"))
    private List<Line> lines = new ArrayList<>();

    protected TitleAmountAdjustment() {
    }

    /**
     * @param balances saldo atual de cada componente do título (do razão)
     * @param targets  novo saldo pedido para cada componente alterado
     * @param threshold         limite da assessoria para aplicar redução sem segunda pessoa
     * @param recentReductions  soma das reduções já aplicadas sem segunda pessoa ({@code WITHIN_LIMIT}) neste título na
     *                          janela do limite acumulado, lida com o título travado
     */
    public TitleAmountAdjustment(DebtTitle title, Map<LedgerComponent, Money> balances,
                                 Map<LedgerComponent, Money> targets, AmountAdjustmentReason reason, String note,
                                 UUID disputeId, Evidence evidence, Money threshold, Money recentReductions,
                                 UUID requestedBy, String requestedByName, Instant now) {
        Guard.require(reason != null, "title_amount.reason_required", "Escolha o motivo da correção");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= NOTE_MAX,
                "title_amount.note_required", "Descreva a correção (até %d caracteres)".formatted(NOTE_MAX));
        Guard.require(targets != null && !targets.isEmpty(), "title_amount.components_required",
                "Informe o novo valor de ao menos um componente");
        Guard.require(threshold != null && !threshold.isNegative(), "title_amount.threshold_invalid", "Limite inválido");
        Guard.require(recentReductions != null && !recentReductions.isNegative(), "title_amount.recent_invalid",
                "Reduções recentes inválidas");
        Money deltaTotal = Money.ZERO;
        Money increases = Money.ZERO;
        Money decreases = Money.ZERO;
        List<Line> created = new ArrayList<>();
        for (Map.Entry<LedgerComponent, Money> target : new EnumMap<>(targets).entrySet()) {
            Money current = balances.getOrDefault(target.getKey(), Money.ZERO);
            Money wanted = target.getValue();
            Guard.require(wanted != null && !wanted.isNegative(), "title_amount.component_negative",
                    "O novo valor de %s não pode ser negativo".formatted(target.getKey()));
            if (wanted.equals(current)) {
                continue;
            }
            Money change = wanted.minus(current);
            created.add(new Line(target.getKey(), current, wanted));
            deltaTotal = deltaTotal.plus(change);
            if (change.isPositive()) {
                increases = increases.plus(change);
            } else {
                decreases = decreases.plus(change.negate());
            }
        }
        Guard.require(!created.isEmpty(), "title_amount.no_change", "Os valores informados são iguais aos atuais");
        Guard.require(!deltaTotal.isZero(), "title_amount.zero_delta",
                "A correção não altera o saldo; para mover valor entre componentes, corrija cada um");
        Money openAfter = title.getOpenBalance().plus(deltaTotal);
        Guard.require(openAfter.isPositive(), "title_amount.would_settle",
                "A correção zeraria o saldo: para encerrar a cobrança, cancele o título");
        Money principalDelta = created.stream().filter(l -> l.component == LedgerComponent.PRINCIPAL)
                .map(l -> l.newBalance.minus(l.previousBalance)).findFirst().orElse(Money.ZERO);
        Money principalAfter = title.getPrincipalAmount().plus(principalDelta);
        Guard.require(!principalAfter.isNegative(), "title_amount.principal_negative", "Principal não pode ficar negativo");
        // dentro do limite só a redução pura: nenhum componente sobe e a soma das reduções cabe no limite. O líquido
        // esconderia a troca entre componentes (ex.: principal por honorários, receita da assessoria) — decisões 803 e 806.
        // Somada às reduções recentes do título: sem isso, reduções sucessivas de até o limite somariam qualquer valor
        // sem segunda pessoa (decisão do responsável sobre a 864, no modelo do pagamento externo fracionado, decisão 309)
        boolean withinAlone = isPureReductionWithin(increases, decreases, Money.ZERO, threshold);
        this.approvalMode = isPureReductionWithin(increases, decreases, recentReductions, threshold)
                ? AmountAdjustmentMode.WITHIN_LIMIT : AmountAdjustmentMode.DUAL_CONTROL;
        this.cumulativeLimitReached = withinAlone && approvalMode == AmountAdjustmentMode.DUAL_CONTROL;
        if (approvalMode == AmountAdjustmentMode.DUAL_CONTROL) {
            Guard.require(evidence != null || disputeId != null, "title_amount.evidence_required",
                    cumulativeLimitReached
                            ? "Somada às reduções já aplicadas neste título nos últimos dias, a correção passa do limite"
                            + " da assessoria: vai para aprovação de outra pessoa; anexe a evidência ou vincule a contestação"
                            : "Acima do limite ou com aumento de algum componente, anexe a evidência ou vincule a contestação");
        }
        this.requestThreshold = threshold;
        this.requestRecentReductions = recentReductions;
        this.titleId = title.getId();
        this.creditorId = title.getCreditorId();
        this.reasonCode = reason;
        this.reasonNote = note.trim();
        this.disputeId = disputeId;
        if (evidence != null) {
            this.evidenceKey = evidence.key();
            this.evidenceName = evidence.name();
            this.evidenceContentType = evidence.contentType();
            this.evidenceSize = evidence.size();
            this.evidenceSha256 = evidence.sha256();
        }
        this.previousPrincipal = title.getPrincipalAmount();
        this.newPrincipal = principalAfter;
        this.previousOpenBalance = title.getOpenBalance();
        this.newOpenBalance = openAfter;
        this.delta = deltaTotal;
        this.requestedBy = Objects.requireNonNull(requestedBy);
        this.requestedByName = truncate(requestedByName);
        this.requestedAt = now;
        this.lines = created;
        this.status = AmountAdjustmentStatus.PENDING_APPROVAL;
    }

    /**
     * Regra da alçada (RB06): aplica sem segunda pessoa só a redução pura — nenhum componente aumenta e a soma das
     * reduções (não o líquido), mais as reduções já aplicadas sem segunda pessoa no mesmo título na janela do limite
     * acumulado, cabe no limite. A prévia da tela segue a mesma regra.
     */
    public static boolean isPureReductionWithin(Money increases, Money decreases, Money recentReductions,
                                                Money threshold) {
        return increases.isZero() && decreases.isPositive()
                && decreases.plus(recentReductions).compareTo(threshold) <= 0;
    }

    /** Por que o pedido precisa de segunda pessoa: aumento de componente, redução acima do limite ou somada às recentes. */
    public enum ApprovalReason { INCREASE, REDUCTION_ABOVE_LIMIT, CUMULATIVE_ABOVE_LIMIT }

    /**
     * Quanto o pedido passa da alçada de quem corrige (tela de aprovação).
     *
     * @param increases        soma dos aumentos de componente (qualquer aumento exige segunda pessoa)
     * @param decreases        soma das reduções de componente (não o líquido)
     * @param recentReductions reduções já aplicadas sem segunda pessoa no título na janela, no pedido
     * @param threshold        limite da assessoria no pedido
     * @param excess           quanto as reduções (com as recentes) passam do limite; zero quando o motivo é só aumento
     */
    public record ApprovalTrigger(ApprovalReason reason, Money increases, Money decreases, Money recentReductions,
                                  Money threshold, Money excess) {
    }

    /**
     * Motivo e excesso do pedido que foi para a segunda pessoa, com o limite e as reduções recentes gravados no pedido
     * (pedido anterior à gravação: {@code currentThreshold} e reduções recentes zero). Nulo para pedido aplicado dentro
     * do limite.
     */
    public ApprovalTrigger approvalTrigger(Money currentThreshold) {
        if (approvalMode != AmountAdjustmentMode.DUAL_CONTROL) {
            return null;
        }
        Money increases = Money.ZERO;
        Money decreases = Money.ZERO;
        for (Line l : lines) {
            Money change = l.newBalance.minus(l.previousBalance);
            if (change.isPositive()) {
                increases = increases.plus(change);
            } else {
                decreases = decreases.plus(change.negate());
            }
        }
        Money threshold = requestThreshold != null ? requestThreshold : currentThreshold;
        Money recent = requestRecentReductions != null ? requestRecentReductions : Money.ZERO;
        Money excess = decreases.plus(recent).minus(threshold).max(Money.ZERO);
        ApprovalReason reason = increases.isPositive() ? ApprovalReason.INCREASE
                : decreases.compareTo(threshold) > 0 ? ApprovalReason.REDUCTION_ABOVE_LIMIT
                : ApprovalReason.CUMULATIVE_ABOVE_LIMIT;
        return new ApprovalTrigger(reason, increases, decreases, recent, threshold, excess);
    }

    /** A redução caberia sozinha no limite, mas não somada às recentes do título (só no pedido recém-criado). */
    public boolean isCumulativeLimitReached() {
        return cumulativeLimitReached;
    }

    /** Aplicada no registro (dentro do limite) ou na aprovação. */
    public void markApplied(UUID by, String byName, String note, Instant now) {
        requirePending();
        if (approvalMode == AmountAdjustmentMode.DUAL_CONTROL) {
            Guard.requireState(!requestedBy.equals(by), "title_amount.self_approval",
                    "Quem pediu a correção não pode aprová-la");
            decide(by, byName, note, now);
        }
        this.status = AmountAdjustmentStatus.APPLIED;
        this.appliedAt = now;
    }

    public void reject(UUID by, String byName, String note, Instant now) {
        requirePending();
        Guard.requireState(approvalMode == AmountAdjustmentMode.DUAL_CONTROL, "title_amount.not_pending",
                "Pedido não aguarda aprovação");
        Guard.requireState(!requestedBy.equals(by), "title_amount.self_approval",
                "Quem pediu a correção não pode recusá-la");
        Guard.require(note != null && !note.isBlank(), "title_amount.rejection_reason_required",
                "Informe o motivo da recusa");
        decide(by, byName, note, now);
        this.status = AmountAdjustmentStatus.REJECTED;
    }

    public void cancel(UUID by, String byName, String note, Instant now) {
        requirePending();
        Guard.requireState(requestedBy.equals(by), "title_amount.cancel_not_owner", "Só quem pediu retira o pedido");
        decide(by, byName, note, now);
        this.status = AmountAdjustmentStatus.CANCELLED;
    }

    /** Os saldos atuais ainda são os do pedido? Senão, a aprovação aplicaria valores sobre outra base. */
    public boolean matchesCurrent(Map<LedgerComponent, Money> balances, DebtTitle title) {
        return title.getPrincipalAmount().equals(previousPrincipal) && lines.stream()
                .allMatch(l -> balances.getOrDefault(l.component, Money.ZERO).equals(l.previousBalance));
    }

    /** Lançamentos (com sinal) por componente. */
    public Map<LedgerComponent, Money> deltas() {
        Map<LedgerComponent, Money> out = new EnumMap<>(LedgerComponent.class);
        lines.forEach(l -> out.put(l.component, l.newBalance.minus(l.previousBalance)));
        return out;
    }

    public boolean changesPrincipal() {
        return !newPrincipal.equals(previousPrincipal);
    }

    private void decide(UUID by, String byName, String note, Instant now) {
        this.decidedBy = by;
        this.decidedByName = truncate(byName);
        this.decidedAt = now;
        this.decisionNote = note == null || note.isBlank() ? null : note.trim().substring(0, Math.min(note.trim().length(), NOTE_MAX));
    }

    private void requirePending() {
        Guard.requireState(status == AmountAdjustmentStatus.PENDING_APPROVAL, "title_amount.not_pending",
                "Pedido de correção já decidido");
    }

    private static String truncate(String name) {
        return name == null ? null : name.length() <= NAME_MAX ? name : name.substring(0, NAME_MAX);
    }

    /** Evidência anexada (arquivo já gravado no armazenamento). */
    public record Evidence(String key, String name, String contentType, long size, String sha256) {
    }

    /** Componente afetado: saldo anterior e novo. */
    @Embeddable
    public static class Line {

        @Enumerated(EnumType.STRING)
        @Column(name = "component", nullable = false, length = 20)
        private LedgerComponent component;

        @Column(name = "previous_balance", nullable = false, precision = 19, scale = 2)
        private Money previousBalance;

        @Column(name = "new_balance", nullable = false, precision = 19, scale = 2)
        private Money newBalance;

        protected Line() {
        }

        Line(LedgerComponent component, Money previousBalance, Money newBalance) {
            this.component = component;
            this.previousBalance = previousBalance;
            this.newBalance = newBalance;
        }

        public LedgerComponent getComponent() {
            return component;
        }

        public Money getPreviousBalance() {
            return previousBalance;
        }

        public Money getNewBalance() {
            return newBalance;
        }
    }

    public UUID getTitleId() {
        return titleId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public AmountAdjustmentStatus getStatus() {
        return status;
    }

    public AmountAdjustmentMode getApprovalMode() {
        return approvalMode;
    }

    public AmountAdjustmentReason getReasonCode() {
        return reasonCode;
    }

    public String getReasonNote() {
        return reasonNote;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public java.util.Optional<Evidence> evidence() {
        return evidenceKey == null ? java.util.Optional.empty()
                : java.util.Optional.of(new Evidence(evidenceKey, evidenceName, evidenceContentType,
                evidenceSize == null ? 0 : evidenceSize, evidenceSha256));
    }

    public Money getPreviousPrincipal() {
        return previousPrincipal;
    }

    public Money getNewPrincipal() {
        return newPrincipal;
    }

    public Money getPreviousOpenBalance() {
        return previousOpenBalance;
    }

    public Money getNewOpenBalance() {
        return newOpenBalance;
    }

    public Money getDelta() {
        return delta;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public String getRequestedByName() {
        return requestedByName;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDecidedByName() {
        return decidedByName;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public List<Line> getLines() {
        return lines.stream().sorted(Comparator.comparing(Line::getComponent)).toList();
    }
}
