package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Contrato de origem no credor (financiamento, cartão, crediário...), que agrupa as parcelas
 * (títulos) de uma mesma operação. Identificado pelo número do contrato no credor.
 */
@Entity
@Table(name = "origin_contract", schema = "debt")
public class OriginContract extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    /** Carteira em que o contrato foi recebido; a carteira atual de cada parcela fica no título. */
    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "external_id", nullable = false, length = 80, updatable = false)
    private String externalId;

    @Column(name = "product", length = 120)
    private String product;

    @Column(name = "signed_at")
    private LocalDate signedAt;

    @Column(name = "original_amount", precision = 19, scale = 2)
    private Money originalAmount;

    protected OriginContract() {
    }

    public OriginContract(UUID creditorId, UUID portfolioId, UUID debtorId, String externalId, String product,
                          LocalDate signedAt, Money originalAmount) {
        Guard.require(creditorId != null, "origin_contract.creditor_required", "Credor obrigatório");
        Guard.require(debtorId != null, "origin_contract.debtor_required", "Devedor obrigatório");
        this.creditorId = creditorId;
        this.debtorId = debtorId;
        this.externalId = DebtTitle.validExternalId(externalId, "origin_contract");
        this.portfolioId = Objects.requireNonNull(portfolioId, "portfolioId");
        update(product, signedAt, originalAmount);
    }

    /** @return true se algum dado mudou */
    public boolean update(String product, LocalDate signedAt, Money originalAmount) {
        String newProduct = product == null || product.isBlank() ? null : product.trim();
        Guard.require(newProduct == null || newProduct.length() <= 120, "origin_contract.product_too_long",
                "Produto muito longo");
        Guard.require(originalAmount == null || !originalAmount.isNegative(), "origin_contract.amount_invalid",
                "Valor original não pode ser negativo");
        boolean changed = !Objects.equals(newProduct, this.product) || !Objects.equals(signedAt, this.signedAt)
                || !Objects.equals(originalAmount, this.originalAmount);
        this.product = newProduct;
        this.signedAt = signedAt;
        this.originalAmount = originalAmount;
        return changed;
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

    public String getExternalId() {
        return externalId;
    }

    public String getProduct() {
        return product;
    }

    public LocalDate getSignedAt() {
        return signedAt;
    }

    public Money getOriginalAmount() {
        return originalAmount;
    }
}
