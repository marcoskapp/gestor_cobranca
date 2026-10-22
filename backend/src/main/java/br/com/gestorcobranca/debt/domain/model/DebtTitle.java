package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Título (parcela original da dívida) de um devedor num credor/carteira, identificado pelo
 * {@code externalId} do credor (RB01).
 * <p>
 * O valor devido é o razão do título ({@link TitleLedgerEntry}); {@code openBalance} e
 * {@code paidTotal} são um cache do razão, atualizado na mesma transação de cada lançamento
 * por {@link #recordPosting}. Regras de status ({@link TitleStatus}):
 * <ul>
 *   <li>saldo zerado por crédito (pagamento, desconto, baixa) → {@code SETTLED} automático;</li>
 *   <li>estorno que torna o saldo positivo num título quitado → {@code OPEN};</li>
 *   <li>títulos {@code SETTLED}, {@code CANCELLED} e {@code RETURNED} não aceitam pagamentos nem
 *       encargos; estornos são aceitos em qualquer status (o dinheiro devolvido precisa constar).</li>
 * </ul>
 */
@Entity
@Table(name = "debt_title", schema = "debt")
public class DebtTitle extends TenantScopedEntity {

    private static final int EXTERNAL_ID_MAX = 80;
    private static final int REASON_MAX = 500;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "origin_contract_id")
    private UUID originContractId;

    @Column(name = "external_id", nullable = false, length = EXTERNAL_ID_MAX, updatable = false)
    private String externalId;

    @Column(name = "installment_number")
    private Integer installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 2)
    private Money principalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TitleStatus status;

    @Column(name = "status_reason", length = REASON_MAX)
    private String statusReason;

    @Column(name = "dispute_blocked", nullable = false)
    private boolean disputeBlocked;

    @Column(name = "dispute_id")
    private UUID disputeId;

    @Column(name = "open_balance", nullable = false, precision = 19, scale = 2)
    private Money openBalance;

    @Column(name = "paid_total", nullable = false, precision = 19, scale = 2)
    private Money paidTotal;

    @Column(name = "last_payment_at")
    private Instant lastPaymentAt;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    protected DebtTitle() {
    }

    /**
     * Cria o título com saldo zero; o valor original entra pelo lançamento de abertura
     * ({@code TitleLedger.open}), mantendo o razão como única fonte do saldo.
     */
    public DebtTitle(UUID creditorId, UUID portfolioId, UUID debtorId, UUID originContractId, String externalId,
                     Integer installmentNumber, LocalDate dueDate, Money principalAmount, UUID importBatchId) {
        Guard.require(creditorId != null, "title.creditor_required", "Credor obrigatório");
        Guard.require(portfolioId != null, "title.portfolio_required", "Carteira obrigatória");
        Guard.require(debtorId != null, "title.debtor_required", "Devedor obrigatório");
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.debtorId = debtorId;
        this.externalId = validExternalId(externalId, "title");
        this.principalAmount = validPrincipal(principalAmount);
        this.status = TitleStatus.OPEN;
        this.openBalance = Money.ZERO;
        this.paidTotal = Money.ZERO;
        this.importBatchId = importBatchId;
        updateTerms(originContractId, installmentNumber, dueDate);
    }

    static String validExternalId(String externalId, String prefix) {
        Guard.require(externalId != null && !externalId.isBlank(), prefix + ".external_id_required",
                "Identificador externo obrigatório");
        String trimmed = externalId.trim();
        Guard.require(trimmed.length() <= EXTERNAL_ID_MAX, prefix + ".external_id_too_long",
                "Identificador externo deve ter até %d caracteres".formatted(EXTERNAL_ID_MAX));
        return trimmed;
    }

    private static Money validPrincipal(Money principal) {
        Guard.require(principal != null && principal.isPositive(), "title.principal_invalid",
                "Valor principal deve ser positivo");
        return principal;
    }

    // ---------------------------------------------------------------- dados da carga

    /** @return true se algum dado mudou */
    public boolean updateTerms(UUID originContractId, Integer installmentNumber, LocalDate dueDate) {
        Guard.require(dueDate != null, "title.due_date_required", "Vencimento obrigatório");
        Guard.require(installmentNumber == null || installmentNumber > 0, "title.installment_invalid",
                "Número da parcela deve ser positivo");
        boolean changed = !Objects.equals(this.originContractId, originContractId)
                || !Objects.equals(this.installmentNumber, installmentNumber) || !dueDate.equals(this.dueDate);
        this.originContractId = originContractId;
        this.installmentNumber = installmentNumber;
        this.dueDate = dueDate;
        return changed;
    }

    /** Registra a carga que atualizou o título por último. */
    public void touchedByImport(UUID importBatchId) {
        this.importBatchId = importBatchId;
    }

    /**
     * Altera o principal informado pelo credor. Chamado somente pelo razão
     * ({@code TitleLedger.adjustPrincipal}), que lança o {@code ADJUSTMENT} correspondente.
     */
    public void changePrincipal(Money newPrincipal) {
        requireAcceptsPosting(LedgerEntryType.ADJUSTMENT);
        this.principalAmount = validPrincipal(newPrincipal);
    }

    /** Títulos quitados, cancelados ou devolvidos não são alterados por novas cargas. */
    public boolean isLockedForImport() {
        return !status.acceptsPostings();
    }

    // ---------------------------------------------------------------- razão

    /** Verifica se o título aceita um lançamento do tipo informado. */
    public void requireAcceptsPosting(LedgerEntryType type) {
        if (type == LedgerEntryType.REVERSAL) {
            return;
        }
        Guard.requireState(status.acceptsPostings(), "title.not_accepting_postings",
                "Título com status %s não aceita lançamentos do tipo %s".formatted(status, type));
    }

    /**
     * Atualiza o cache do razão após um lançamento e aplica as transições automáticas de status.
     *
     * @return status anterior
     */
    public TitleStatus recordPosting(LedgerEntryType type, Money newOpenBalance, Money newPaidTotal,
                                     Instant newLastPaymentAt) {
        requireAcceptsPosting(type);
        Guard.require(newOpenBalance != null && !newOpenBalance.isNegative(), "title.balance_negative",
                "Saldo do título não pode ficar negativo");
        Guard.require(newPaidTotal != null && !newPaidTotal.isNegative(), "title.paid_negative",
                "Total pago não pode ficar negativo");
        TitleStatus previous = status;
        this.openBalance = newOpenBalance;
        this.paidTotal = newPaidTotal;
        this.lastPaymentAt = newLastPaymentAt;
        if (newOpenBalance.isZero() && status.acceptsPostings() && type != LedgerEntryType.OPENING) {
            moveTo(TitleStatus.SETTLED, null);
        } else if (newOpenBalance.isPositive() && status == TitleStatus.SETTLED) {
            moveTo(TitleStatus.OPEN, null);
        }
        return previous;
    }

    // ---------------------------------------------------------------- ciclo de vida manual

    /** @return status anterior */
    public TitleStatus suspend(String reason) {
        return manualTransition(TitleStatus.SUSPENDED, requiredReason(reason));
    }

    /** Retoma a cobrança de um título suspenso. @return status anterior */
    public TitleStatus reactivate() {
        Guard.requireState(status == TitleStatus.SUSPENDED, "title.not_suspended", "Somente títulos suspensos podem ser reativados");
        return manualTransition(TitleStatus.OPEN, null);
    }

    /** @return status anterior */
    public TitleStatus cancel(String reason) {
        return manualTransition(TitleStatus.CANCELLED, requiredReason(reason));
    }

    /** Devolução ao credor. A titularidade é encerrada pelo caso de uso. @return status anterior */
    public TitleStatus returnToCreditor(String reason) {
        return manualTransition(TitleStatus.RETURNED, requiredReason(reason));
    }

    /** Move para outra carteira do mesmo credor (a validação do credor é do caso de uso). */
    public void transferTo(UUID targetPortfolioId) {
        Guard.require(targetPortfolioId != null, "title.portfolio_required", "Carteira obrigatória");
        Guard.requireState(!status.isClosed(), "title.closed", "Título cancelado ou devolvido não pode ser transferido");
        Guard.require(!targetPortfolioId.equals(portfolioId), "title.already_in_portfolio", "Título já está nesta carteira");
        this.portfolioId = targetPortfolioId;
    }

    private TitleStatus manualTransition(TitleStatus target, String reason) {
        if (!status.canTransitionTo(target) || target == TitleStatus.SETTLED) {
            throw new ConflictException("title.invalid_transition",
                    "Título não pode passar de %s para %s".formatted(status, target),
                    Map.of("titleId", getId().toString(), "from", status.name(), "to", target.name()));
        }
        TitleStatus previous = status;
        moveTo(target, reason);
        return previous;
    }

    private void moveTo(TitleStatus target, String reason) {
        this.status = target;
        this.statusReason = reason;
    }

    private static String requiredReason(String reason) {
        Guard.require(reason != null && !reason.isBlank(), "title.reason_required", "Informe o motivo");
        Guard.require(reason.length() <= REASON_MAX, "title.reason_too_long", "Motivo muito longo");
        return reason.trim();
    }

    // ---------------------------------------------------------------- contestação (F21)

    /**
     * Vincula o título a uma contestação.
     *
     * @param blocks se a contestação suspende a cobrança enquanto analisada
     * @return true se algo mudou (repetição do mesmo vínculo não altera nada)
     */
    public boolean markDisputed(UUID disputeId, boolean blocks) {
        Guard.require(disputeId != null, "title.dispute_required", "Contestação obrigatória");
        boolean changed = !disputeId.equals(this.disputeId) || this.disputeBlocked != blocks;
        this.disputeId = disputeId;
        this.disputeBlocked = blocks;
        return changed;
    }

    /**
     * Remove o vínculo com a contestação informada. Um vínculo com outra contestação (mais
     * recente) é preservado.
     *
     * @return true se o vínculo foi removido
     */
    public boolean clearDispute(UUID disputeId) {
        if (this.disputeId == null || !this.disputeId.equals(disputeId)) {
            return false;
        }
        this.disputeId = null;
        this.disputeBlocked = false;
        return true;
    }

    // ---------------------------------------------------------------- consultas

    /** Dias de atraso na data informada; zero se em dia ou fora de cobrança. */
    public long daysOverdue(LocalDate today) {
        if (!status.acceptsPostings() || !dueDate.isBefore(today)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(dueDate, today);
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

    public UUID getOriginContractId() {
        return originContractId;
    }

    public String getExternalId() {
        return externalId;
    }

    public Integer getInstallmentNumber() {
        return installmentNumber;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Money getPrincipalAmount() {
        return principalAmount;
    }

    public TitleStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public boolean isDisputeBlocked() {
        return disputeBlocked;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public Money getOpenBalance() {
        return openBalance;
    }

    public Money getPaidTotal() {
        return paidTotal;
    }

    public Instant getLastPaymentAt() {
        return lastPaymentAt;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }
}
