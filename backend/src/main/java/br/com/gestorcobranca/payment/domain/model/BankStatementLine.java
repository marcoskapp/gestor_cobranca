package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Linha de extrato/arquivo de retorno e o resultado da conciliação. A descrição livre do banco pode
 * conter o nome do pagador e fica criptografada. A impressão digital ({@code fingerprint}) identifica a
 * mesma transação em arquivos diferentes: repetida, não gera nova baixa.
 */
@Entity
@Table(name = "statement_line", schema = "payment")
public class BankStatementLine extends TenantScopedEntity {

    @Column(name = "import_id", nullable = false, updatable = false)
    private UUID importId;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Column(name = "paid_on", nullable = false, updatable = false)
    private LocalDate paidOn;

    @Column(name = "amount", nullable = false, updatable = false)
    private Money amount;

    @Column(name = "direction", nullable = false, length = 1, updatable = false)
    private String direction;

    @Column(name = "transaction_id", length = 64, updatable = false)
    private String transactionId;

    @Column(name = "reference", length = 100, updatable = false)
    private String reference;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "description_encrypted", length = 2000, updatable = false)
    private String description;

    @Column(name = "fingerprint", nullable = false, length = 64, updatable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StatementLineStatus status;

    @Column(name = "payment_id")
    private UUID paymentId;

    protected BankStatementLine() {
    }

    public BankStatementLine(UUID importId, int lineNumber, LocalDate paidOn, Money amount, boolean credit,
                         String transactionId, String reference, String description, String fingerprint,
                         StatementLineStatus status, UUID paymentId) {
        Guard.require(importId != null && paidOn != null && fingerprint != null, "statement.line_invalid",
                "Linha de extrato incompleta");
        Guard.require(amount != null && amount.isPositive(), "statement.line_amount_invalid", "Valor da linha inválido");
        this.importId = importId;
        this.lineNumber = lineNumber;
        this.paidOn = paidOn;
        this.amount = amount;
        this.direction = credit ? "C" : "D";
        this.transactionId = transactionId;
        this.reference = reference;
        this.description = description == null || description.isBlank() ? null
                : description.length() > 500 ? description.substring(0, 500) : description;
        this.fingerprint = fingerprint;
        this.status = status;
        this.paymentId = paymentId;
    }

    /** Resultado da conciliação automática. */
    public void outcome(StatementLineStatus status, UUID paymentId) {
        this.status = status;
        this.paymentId = paymentId;
    }

    /** Resolução manual de divergência: a linha passa a apontar o pagamento conciliado. */
    public void reconciledWith(UUID paymentId) {
        this.status = StatementLineStatus.RECONCILED;
        this.paymentId = paymentId;
    }

    public boolean isCredit() {
        return "C".equals(direction);
    }

    public UUID getImportId() {
        return importId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public Money getAmount() {
        return amount;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getReference() {
        return reference;
    }

    public String getDescription() {
        return description;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public StatementLineStatus getStatus() {
        return status;
    }

    public UUID getPaymentId() {
        return paymentId;
    }
}
