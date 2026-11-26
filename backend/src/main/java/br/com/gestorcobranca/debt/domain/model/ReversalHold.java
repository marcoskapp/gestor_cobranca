package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Retenção da cobrança após o estorno de um pagamento recebido pelo credor ("Estorno em conferência", H07 parte 3).
 * Um registro por estorno: o par devedor–credor fica retido enquanto houver ao menos uma retenção
 * {@link ReversalHoldState#PENDING}. Independente do status do título: suspender, reativar, nova carga ou nova
 * quitação não a encerram (RS-109). Só a liberação por uma pessoa ({@link #release}) ou a anonimização/eliminação do
 * titular ({@link #endByAnonymization}) a encerram — nunca um prazo.
 * <p>
 * Segregação (decisão do responsável de 26/11/2026): quem libera nunca é quem estornou nem quem informou o pagamento
 * original (quando usuário interno), inclusive o administrador.
 */
@Entity
@Table(name = "reversal_hold", schema = "debt")
public class ReversalHold extends TenantScopedEntity {

    public static final int MAX_NOTE = 500;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "reversal_id", nullable = false, updatable = false)
    private UUID reversalId;

    @Column(name = "reversal_reason", nullable = false, length = 30, updatable = false)
    private String reversalReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    private ReversalHoldState state;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "placed_by", nullable = false, updatable = false)
    private UUID placedBy;

    @Column(name = "placed_by_type", nullable = false, length = 20, updatable = false)
    private String placedByType;

    @Column(name = "informed_by", updatable = false)
    private UUID informedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason_code", length = 40)
    private ReversalHoldReleaseReason releaseReason;

    @Column(name = "release_note", length = MAX_NOTE)
    private String releaseNote;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ended_reason", length = 30)
    private String endedReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reversal_hold_item", schema = "debt", joinColumns = @JoinColumn(name = "hold_id"))
    private Set<Item> items = new LinkedHashSet<>();

    protected ReversalHold() {
    }

    /**
     * @param placedBy     quem estornou (usuário) ou o id do sistema (devolução informada pelo PSP)
     * @param placedByType {@code USER} ou {@code SYSTEM}
     * @param informedBy   quem informou o pagamento original, só quando usuário interno (nulo nos demais)
     */
    public ReversalHold(UUID creditorId, UUID debtorId, UUID paymentId, UUID reversalId, Collection<UUID> agreementIds,
                        String reversalReason, Collection<UUID> titleIds, Collection<UUID> installmentIds,
                        Collection<UUID> allocationIds, UUID placedBy, String placedByType, UUID informedBy,
                        Instant placedAt) {
        Guard.require(creditorId != null && debtorId != null, "reversal_hold.parties_required",
                "Credor e devedor obrigatórios");
        Guard.require(paymentId != null && reversalId != null, "reversal_hold.reversal_required",
                "Pagamento e estorno obrigatórios");
        Guard.require(reversalReason != null && !reversalReason.isBlank() && reversalReason.length() <= 30,
                "reversal_hold.reason_required", "Motivo do estorno obrigatório");
        Guard.require(placedBy != null && ("USER".equals(placedByType) || "SYSTEM".equals(placedByType)),
                "reversal_hold.placed_by_required", "Autor do estorno obrigatório");
        Guard.require(placedAt != null, "reversal_hold.placed_at_required", "Instante do estorno obrigatório");
        this.creditorId = creditorId;
        this.debtorId = debtorId;
        this.paymentId = paymentId;
        this.reversalId = reversalId;
        this.reversalReason = reversalReason;
        this.placedBy = placedBy;
        this.placedByType = placedByType;
        this.informedBy = informedBy;
        this.placedAt = placedAt;
        this.state = ReversalHoldState.PENDING;
        add(ItemKind.TITLE, titleIds);
        add(ItemKind.AGREEMENT, agreementIds);
        add(ItemKind.INSTALLMENT, installmentIds);
        // sem teto de itens: são só ids, limitados pelas alocações do próprio pagamento; um teto abortaria o
        // estorno inteiro (decisão 1681)
        add(ItemKind.ALLOCATION, allocationIds);
    }

    private void add(ItemKind kind, Collection<UUID> ids) {
        if (ids != null) {
            ids.stream().filter(Objects::nonNull).forEach(id -> items.add(new Item(kind, id)));
        }
    }

    /**
     * Liberação por uma pessoa com {@code PAYMENT_REVIEW} (a permissão é do caso de uso). Ordem das recusas: situação
     * (409), segregação (422), motivo e justificativa (422), confirmação de que conferiu com o credor (409).
     */
    public void release(UUID by, ReversalHoldReleaseReason reason, String note, boolean confirmedWithCreditor,
                        Instant now) {
        Guard.require(by != null, "reversal_hold.releaser_required", "Responsável pela liberação obrigatório");
        Guard.requireState(state == ReversalHoldState.PENDING, "reversal_hold.not_pending",
                "A cobrança deste estorno já foi liberada ou encerrada. Atualize a página.");
        if (isAuthorOrInformer(by)) {
            throw new BusinessRuleException("reversal_hold.self_release",
                    "Quem estornou ou informou o pagamento não pode liberar a cobrança. Peça a outra pessoa do financeiro.");
        }
        Guard.require(reason != null, "reversal_hold.release_reason_required", "Escolha o motivo da liberação");
        String trimmed = note == null ? null : note.trim();
        Guard.require(trimmed != null && !trimmed.isEmpty() && trimmed.length() <= MAX_NOTE,
                "reversal_hold.release_note_required", "Escreva a justificativa (até " + MAX_NOTE + " caracteres)");
        Guard.requireState(confirmedWithCreditor, "reversal_hold.confirmation_required",
                "Confirme que conferiu com o credor que este valor está em aberto");
        this.state = ReversalHoldState.RELEASED;
        this.releasedBy = by;
        this.releasedAt = now;
        this.releaseReason = reason;
        this.releaseNote = trimmed;
    }

    /** Quem estornou (usuário) ou quem informou o pagamento original (usuário interno). */
    public boolean isAuthorOrInformer(UUID userId) {
        return userId != null && (("USER".equals(placedByType) && userId.equals(placedBy)) || userId.equals(informedBy));
    }

    /**
     * Titular anonimizado ou eliminado (LGPD): a retenção pendente termina sem retomar contato (não há com quem cobrar) e
     * a justificativa da liberação é limpa (texto livre pode citar o titular). Devolve se mudou algo.
     */
    public boolean endByAnonymization(Instant now) {
        boolean changed = false;
        if (state == ReversalHoldState.PENDING) {
            state = ReversalHoldState.ENDED;
            endedAt = now;
            endedReason = "DEBTOR_ANONYMIZED";
            changed = true;
        }
        if (releaseNote != null) {
            releaseNote = null;
            changed = true;
        }
        return changed;
    }

    public void requireVersion(long expected) {
        if (getVersion() == null || getVersion() != expected) {
            throw new ConflictException("reversal_hold.version_conflict",
                    "Este estorno mudou desde que você abriu a tela. Atualize a página.");
        }
    }

    public List<UUID> ids(ItemKind kind) {
        return items.stream().filter(i -> i.kind == kind).map(Item::getItemId).toList();
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getReversalId() {
        return reversalId;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public ReversalHoldState getState() {
        return state;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public UUID getPlacedBy() {
        return placedBy;
    }

    public String getPlacedByType() {
        return placedByType;
    }

    public UUID getInformedBy() {
        return informedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public UUID getReleasedBy() {
        return releasedBy;
    }

    public ReversalHoldReleaseReason getReleaseReason() {
        return releaseReason;
    }

    public String getReleaseNote() {
        return releaseNote;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getEndedReason() {
        return endedReason;
    }

    /** Tipo do item afetado pelo estorno. */
    public enum ItemKind { TITLE, AGREEMENT, INSTALLMENT, ALLOCATION }

    @Embeddable
    public static class Item {

        @Enumerated(EnumType.STRING)
        @Column(name = "kind", nullable = false, length = 20)
        private ItemKind kind;

        @Column(name = "item_id", nullable = false)
        private UUID itemId;

        protected Item() {
        }

        Item(ItemKind kind, UUID itemId) {
            this.kind = kind;
            this.itemId = itemId;
        }

        public ItemKind getKind() {
            return kind;
        }

        public UUID getItemId() {
            return itemId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Item other && kind == other.kind && Objects.equals(itemId, other.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, itemId);
        }
    }
}
