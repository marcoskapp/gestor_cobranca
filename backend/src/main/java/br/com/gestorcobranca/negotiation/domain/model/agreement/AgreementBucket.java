package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Posição de um componente do razão de um título dentro do acordo — base da distribuição dos
 * pagamentos e dos lançamentos no razão do módulo debt:
 * <ul>
 *   <li>{@code gross}: valor bruto negociado (inclui encargos já reconhecidos e, nos juros, os juros do parcelamento);</li>
 *   <li>{@code discount}: desconto concedido;</li>
 *   <li>{@code payable} = gross − discount: quanto o devedor paga neste componente;</li>
 *   <li>{@code recognized}: quanto já está lançado como encargo no razão (inicialmente o reconhecido
 *       antes do acordo; cresce à medida que pagamentos exigem reconhecimento);</li>
 *   <li>{@code paid}: quanto dos pagamentos do acordo foi distribuído aqui.</li>
 * </ul>
 */
@Entity
@Table(name = "agreement_bucket", schema = "negotiation")
public class AgreementBucket extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agreement_id", nullable = false, updatable = false)
    private Agreement agreement;

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "part", nullable = false, updatable = false, length = 20)
    private LedgerPart part;

    @Column(name = "gross", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money gross;

    @Column(name = "discount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money discount;

    @Column(name = "payable", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money payable;

    @Column(name = "recognized", nullable = false, precision = 19, scale = 2)
    private Money recognized;

    @Column(name = "paid", nullable = false, precision = 19, scale = 2)
    private Money paid;

    protected AgreementBucket() {
    }

    AgreementBucket(Agreement agreement, UUID titleId, LedgerPart part, Money gross, Money discount, Money recognized) {
        Guard.require(!gross.isNegative() && !discount.isNegative() && !discount.isGreaterThan(gross),
                "agreement.bucket_invalid", "Composição do acordo inválida");
        this.agreement = agreement;
        this.titleId = titleId;
        this.part = part;
        this.gross = gross;
        this.discount = discount;
        this.payable = gross.minus(discount);
        this.recognized = part == LedgerPart.PRINCIPAL ? gross : recognized.min(gross);
        this.paid = Money.ZERO;
    }

    Money remaining() {
        return payable.minus(paid).max(Money.ZERO);
    }

    /** Registra a parte de um pagamento; devolve o encargo a reconhecer antes do lançamento. */
    Money pay(Money value) {
        Guard.requireState(!value.isGreaterThan(remaining()), "agreement.bucket_overpaid",
                "Pagamento excede o saldo do componente " + part);
        paid = paid.plus(value);
        if (part.isCharge() && paid.isGreaterThan(recognized)) {
            Money charge = paid.minus(recognized);
            recognized = paid;
            return charge;
        }
        return Money.ZERO;
    }

    /**
     * Estorno. Se o pagamento já estava no razão, o encargo reconhecido permanece (o debt não estorna
     * reconhecimento); se ainda não tinha sido lançado, o reconhecimento planejado também é desfeito.
     */
    void unpay(Money value, Money plannedCharge) {
        Guard.requireState(!value.isGreaterThan(paid), "agreement.bucket_reversal_invalid", "Estorno maior que o pago");
        paid = paid.minus(value);
        if (plannedCharge != null && plannedCharge.isPositive()) {
            recognized = recognized.minus(plannedCharge);
        }
    }

    /** Encargo que falta reconhecer para que o razão mostre o valor bruto na quitação. */
    Money settleCharges() {
        if (!part.isCharge() || !gross.isGreaterThan(recognized)) {
            return Money.ZERO;
        }
        Money charge = gross.minus(recognized);
        recognized = gross;
        return charge;
    }

    public UUID getTitleId() {
        return titleId;
    }

    public LedgerPart getPart() {
        return part;
    }

    public Money getGross() {
        return gross;
    }

    public Money getDiscount() {
        return discount;
    }

    public Money getPayable() {
        return payable;
    }

    public Money getRecognized() {
        return recognized;
    }

    public Money getPaid() {
        return paid;
    }
}
