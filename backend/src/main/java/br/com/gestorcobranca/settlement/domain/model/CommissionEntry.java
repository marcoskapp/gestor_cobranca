package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator.Rating;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/**
 * Lançamento do razão de comissão da assessoria (append-only, gatilho no banco): um por recebimento
 * apurado, com a versão das regras e a memória de cálculo. Estornos geram lançamentos
 * {@code REVERSAL} negativos ligados ao original. A posição líquida de um recebimento é a soma do
 * original e de seus estornos.
 */
@Entity
@Table(name = "commission_entry", schema = "settlement")
public class CommissionEntry extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 10)
    private EntryKind kind;
    @Column(name = "receipt_id", nullable = false, updatable = false)
    private UUID receiptId;
    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;
    @Column(name = "rule_set_id", nullable = false, updatable = false)
    private UUID ruleSetId;
    @Column(name = "rule_version", nullable = false, updatable = false)
    private int ruleVersion;
    @Column(name = "rule_code", nullable = false, updatable = false, length = 60)
    private String ruleCode;
    @Column(name = "recovered_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money recoveredAmount;
    @Column(name = "agency_honorarium", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money agencyHonorarium;
    @Column(name = "creditor_honorarium", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money creditorHonorarium;
    @Column(name = "commission_base", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money commissionBase;
    @Column(name = "rate", nullable = false, updatable = false, precision = 9, scale = 4)
    private Percentage rate;
    @Column(name = "commission_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money commissionAmount;
    @Column(name = "fixed_fee", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money fixedFee;
    @Column(name = "transfer_effect", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money transferEffect;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation", nullable = false, updatable = false)
    private String calculation;

    protected CommissionEntry() {
    }

    private CommissionEntry(EntryKind kind, Receipt receipt, UUID reversesEntryId, CommissionRuleSet ruleSet,
                            int ruleVersion, UUID ruleSetId, Rating rating, String calculationJson) {
        Guard.require(receipt != null && rating != null, "commission_entry.invalid", "Recebimento e cálculo obrigatórios");
        this.kind = kind;
        this.receiptId = receipt.getId();
        this.reversesEntryId = reversesEntryId;
        this.creditorId = receipt.getCreditorId();
        this.agreementId = receipt.getAgreementId();
        this.ruleSetId = ruleSet != null ? ruleSet.getId() : ruleSetId;
        this.ruleVersion = ruleSet != null ? ruleSet.getRuleVersion() : ruleVersion;
        this.ruleCode = rating.ruleCode();
        this.recoveredAmount = rating.recovered();
        this.agencyHonorarium = rating.agencyHonorarium();
        this.creditorHonorarium = rating.creditorHonorarium();
        this.commissionBase = rating.commissionBase();
        this.rate = rating.rate();
        this.commissionAmount = rating.commission();
        this.fixedFee = rating.fixedFee();
        this.transferEffect = rating.transferEffect();
        this.calculation = calculationJson;
    }

    public static CommissionEntry accrual(Receipt receipt, CommissionRuleSet ruleSet, Rating rating, String calculationJson) {
        Guard.require(receipt.isPayment(), "commission_entry.kind_mismatch", "Apuração exige recebimento confirmado");
        return new CommissionEntry(EntryKind.ACCRUAL, receipt, null, ruleSet, 0, null, rating, calculationJson);
    }

    /** Compensação com a mesma versão de regras do lançamento original. */
    public static CommissionEntry reversal(Receipt reversalReceipt, CommissionEntry original, Rating rating,
                                           String calculationJson) {
        Guard.require(!reversalReceipt.isPayment() && original.kind == EntryKind.ACCRUAL,
                "commission_entry.kind_mismatch", "Compensação exige recebimento de estorno e lançamento original");
        return new CommissionEntry(EntryKind.REVERSAL, reversalReceipt, original.getId(), null, original.ruleVersion,
                original.ruleSetId, rating, calculationJson);
    }

    /** Valores do lançamento como resultado de cálculo (posição). */
    public Rating asRating() {
        return new Rating(ruleCode, rate, recoveredAmount, agencyHonorarium, creditorHonorarium, commissionBase,
                commissionAmount, fixedFee, transferEffect, List.of());
    }

    public EntryKind getKind() { return kind; }
    public UUID getReceiptId() { return receiptId; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getAgreementId() { return agreementId; }
    public UUID getRuleSetId() { return ruleSetId; }
    public int getRuleVersion() { return ruleVersion; }
    public String getRuleCode() { return ruleCode; }
    public Money getRecoveredAmount() { return recoveredAmount; }
    public Money getAgencyHonorarium() { return agencyHonorarium; }
    public Money getCreditorHonorarium() { return creditorHonorarium; }
    public Money getCommissionBase() { return commissionBase; }
    public Percentage getRate() { return rate; }
    public Money getCommissionAmount() { return commissionAmount; }
    public Money getFixedFee() { return fixedFee; }
    public Money getTransferEffect() { return transferEffect; }
    public String getCalculation() { return calculation; }
}
