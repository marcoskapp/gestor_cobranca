package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lançamento do razão do título (append-only: o banco recusa UPDATE/DELETE). Correções são
 * novos lançamentos compensatórios, nunca alteração do original (RB06). O sinal do valor segue
 * a convenção de {@link LedgerEntryType}.
 * <p>
 * {@code (titleId, entryType, referenceType, referenceId, component)} é único: repetir a
 * aplicação do mesmo pagamento/acordo/carga não duplica o efeito.
 */
@Entity
@Immutable
@Table(name = "title_ledger_entry", schema = "debt")
public class TitleLedgerEntry extends TenantScopedEntity {

    public static final String REF_PAYMENT = "payment";
    public static final String REF_AGREEMENT = "agreement";
    public static final String REF_IMPORT_BATCH = "import_batch";

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private LedgerEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "component", nullable = false, length = 20, updatable = false)
    private LedgerComponent component;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money amount;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "reference_type", nullable = false, length = 40, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    protected TitleLedgerEntry() {
    }

    public TitleLedgerEntry(UUID titleId, LedgerEntryType entryType, LedgerComponent component, Money amount,
                            Instant occurredAt, String referenceType, UUID referenceId, String reason) {
        this.titleId = Objects.requireNonNull(titleId, "titleId");
        this.entryType = Objects.requireNonNull(entryType, "entryType");
        this.component = Objects.requireNonNull(component, "component");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        Guard.require(entryType.acceptsAmount(amount), "ledger.amount_sign_invalid",
                "Valor com sinal incompatível com o tipo de lançamento %s".formatted(entryType));
        Guard.require(referenceType != null && !referenceType.isBlank() && referenceType.length() <= 40,
                "ledger.reference_type_required", "Tipo de referência do lançamento obrigatório");
        Guard.require(referenceId != null, "ledger.reference_required", "Referência do lançamento obrigatória");
        Guard.require(reason == null || reason.length() <= 500, "ledger.reason_too_long", "Motivo muito longo");
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public boolean refersTo(LedgerEntryType type, String referenceType, UUID referenceId) {
        return this.entryType == type && this.referenceType.equals(referenceType) && this.referenceId.equals(referenceId);
    }

    public UUID getTitleId() {
        return titleId;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public LedgerComponent getComponent() {
        return component;
    }

    public Money getAmount() {
        return amount;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getReason() {
        return reason;
    }
}
