package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
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
 * {@code paidTotal} (com {@code creditorPaidTotal}, a parte recebida diretamente pelo credor) são um cache do razão,
 * atualizado na mesma transação de cada lançamento
 * por {@link #recordPosting}. Regras de status ({@link TitleStatus}):
 * <ul>
 *   <li>saldo zerado por crédito (pagamento, desconto, baixa) → {@code SETTLED} automático;</li>
 *   <li>estorno que torna o saldo positivo num título quitado → {@code OPEN} (com o titular eliminado, o título fica
 *       quitado com o saldo: {@link #keepSettledAfterErasure});</li>
 *   <li>títulos {@code SETTLED}, {@code CANCELLED} e {@code RETURNED} não aceitam pagamentos nem
 *       encargos; estornos são aceitos em qualquer status (o dinheiro devolvido precisa constar).</li>
 * </ul>
 */
@Entity
@Table(name = "debt_title", schema = "debt")
public class DebtTitle extends TenantScopedEntity {

    private static final int EXTERNAL_ID_MAX = 80;
    private static final int REASON_MAX = 500;
    /** Motivo do título que fica quitado depois do estorno, com o titular eliminado (decisão 1603). */
    public static final String ERASED_HOLDER_REASON = "Estorno depois da eliminação do titular: saldo registrado, sem cobrança";

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "origin_contract_id")
    private UUID originContractId;

    /**
     * Identificador no credor; com o titular anonimizado e o documento embutido, {@code anon:} + HMAC (decisão 1476),
     * trocado só por {@link #anonymizeExternalId} (o gatilho do banco recusa qualquer outra troca).
     */
    @Column(name = "external_id", nullable = false, length = EXTERNAL_ID_MAX)
    private String externalId;

    /** Forma mascarada para exibição, gravada na troca do identificador (decisão 1476); nula enquanto não trocado. */
    @Column(name = "external_id_display", insertable = false, columnDefinition = "text")
    private String externalIdDisplay;

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

    /** Parte de {@code paidTotal} recebida diretamente pelo credor (líquida de estornos): selo "Pago ao credor". */
    @Column(name = "creditor_paid_total", nullable = false, precision = 19, scale = 2)
    private Money creditorPaidTotal;

    @Column(name = "last_payment_at")
    private Instant lastPaymentAt;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    /** Principal corrigido manualmente pela assessoria: a carga do credor não o sobrescreve em silêncio. */
    @Column(name = "manual_principal", nullable = false)
    private boolean manualPrincipal;

    /** Último principal informado pelo credor numa carga (ou na criação). */
    @Column(name = "creditor_principal", nullable = false, precision = 19, scale = 2)
    private Money creditorPrincipal;

    /** Principal do credor já reconhecido numa divergência resolvida mantendo a correção (não reabre). */
    @Column(name = "acknowledged_principal", precision = 19, scale = 2)
    private Money acknowledgedPrincipal;

    /** Lote da carga do credor cuja redução de principal levou o título a {@code SETTLED} (H07 2b); nulo nos demais. */
    @Column(name = "settled_by_import_batch_id")
    private UUID settledByImportBatchId;

    @Column(name = "settled_by_import_at")
    private Instant settledByImportAt;

    /** Motivo codificado da suspensão pela carga ("credor informou pagamento/cancelamento"); nulo nas demais. */
    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_reason_code", length = 30)
    private TitleSuspensionReason suspensionReasonCode;

    @Column(name = "suspension_import_batch_id")
    private UUID suspensionImportBatchId;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    /** Como a carga do credor deve tratar o principal informado. */
    public enum CreditorPrincipal {
        /** Sem correção manual: o valor do credor vale (ajuste no razão se diferente). */
        APPLY,
        /** Com correção manual, o credor passou a informar o mesmo valor: a correção deixa de ser manual. */
        CONVERGED,
        /** Com correção manual, valor do credor já reconhecido numa decisão anterior: nada a fazer. */
        ACKNOWLEDGED,
        /** Com correção manual, valor diferente: mantém a correção e sinaliza a divergência. */
        DIVERGENT
    }

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
        this.creditorPrincipal = this.principalAmount;
        this.status = TitleStatus.OPEN;
        this.openBalance = Money.ZERO;
        this.paidTotal = Money.ZERO;
        this.creditorPaidTotal = Money.ZERO;
        this.importBatchId = importBatchId;
        updateTerms(originContractId, installmentNumber, dueDate);
    }

    static String validExternalId(String externalId, String prefix) {
        Guard.require(externalId != null && !externalId.isBlank(), prefix + ".external_id_required",
                "Identificador externo obrigatório");
        String trimmed = ExternalIdDigest.canonical(externalId);
        Guard.require(trimmed != null && !ExternalIdDigest.hasControl(trimmed), prefix + ".external_id_invalid",
                "Identificador externo com caractere inválido");
        Guard.require(trimmed.length() <= EXTERNAL_ID_MAX, prefix + ".external_id_too_long",
                "Identificador externo deve ter até %d caracteres".formatted(EXTERNAL_ID_MAX));
        // reservado à forma anonimizada (decisão 1476): a entrada nunca alcança nem imita um identificador trocado
        Guard.require(!ExternalIdDigest.isReserved(trimmed), prefix + ".external_id_reserved",
                "Identificador externo não pode começar com \"%s\"".formatted(ExternalIdDigest.PREFIX));
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

    /**
     * Saldo zerado pela carga do credor (H07 2b): chamado só pelo caminho da carga, logo depois do ajuste de principal
     * que levou o título a {@code SETTLED}, com o lote em processamento. Fora de {@code SETTLED} não grava nada; a saída
     * de {@code SETTLED} (reabertura) limpa o lote.
     *
     * @return se gravou
     */
    public boolean markSettledByImport(UUID importBatchId, Instant at) {
        Guard.require(importBatchId != null && at != null, "title.import_batch_required", "Lote de importação obrigatório");
        if (status != TitleStatus.SETTLED) {
            return false;
        }
        this.settledByImportBatchId = importBatchId;
        this.settledByImportAt = at;
        return true;
    }

    /**
     * Suspensão pela carga porque o credor informou o título como pago ou cancelado (H07 2b): motivo em texto (como
     * antes) e codificado, com o lote em processamento. A saída de {@code SUSPENDED} limpa o motivo codificado.
     *
     * @return status anterior
     */
    public TitleStatus suspendReportedByCreditor(TitleSuspensionReason code, String reason, UUID importBatchId,
                                                 Instant at) {
        Guard.require(code != null && importBatchId != null && at != null, "title.import_batch_required",
                "Lote de importação obrigatório");
        TitleStatus previous = manualTransition(TitleStatus.SUSPENDED, requiredReason(reason));
        this.suspensionReasonCode = code;
        this.suspensionImportBatchId = importBatchId;
        this.suspendedAt = at;
        return previous;
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

    /**
     * Classifica o principal informado pelo credor numa carga e registra-o como o último do credor. Com correção manual,
     * só a convergência (credor passou a informar o valor corrigido) devolve o título ao fluxo normal.
     */
    public CreditorPrincipal receiveCreditorPrincipal(Money reported) {
        Guard.require(reported != null, "title.principal_invalid", "Valor principal obrigatório");
        this.creditorPrincipal = reported;
        if (!manualPrincipal) {
            return CreditorPrincipal.APPLY;
        }
        if (reported.equals(principalAmount)) {
            this.manualPrincipal = false;
            this.acknowledgedPrincipal = null;
            return CreditorPrincipal.CONVERGED;
        }
        return reported.equals(acknowledgedPrincipal) ? CreditorPrincipal.ACKNOWLEDGED : CreditorPrincipal.DIVERGENT;
    }

    /**
     * Correção manual do principal (pela assessoria). Chamado somente pelo razão ({@code TitleLedger.adjustManually}),
     * que lança o {@code ADJUSTMENT} correspondente.
     */
    public void changePrincipalManually(Money newPrincipal) {
        requireAcceptsPosting(LedgerEntryType.ADJUSTMENT);
        Guard.require(newPrincipal != null && !newPrincipal.isNegative(), "title.principal_invalid",
                "Valor principal não pode ser negativo");
        this.principalAmount = newPrincipal;
        this.manualPrincipal = !newPrincipal.equals(creditorPrincipal);
        this.acknowledgedPrincipal = null;
    }

    /** Divergência resolvida mantendo a correção: o valor do credor fica reconhecido. */
    public void acknowledgeCreditorPrincipal(Money reported) {
        Guard.requireState(manualPrincipal, "title_amount.not_manual", "O principal do título não foi corrigido manualmente");
        this.acknowledgedPrincipal = reported;
    }

    /** Divergência resolvida aceitando o valor do credor (o razão lança o ajuste pela via da carga). */
    public void dropManualPrincipal() {
        this.manualPrincipal = false;
        this.acknowledgedPrincipal = null;
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
     * @param newCreditorPaidTotal parte de {@code newPaidTotal} recebida diretamente pelo credor
     * @return status anterior
     */
    public TitleStatus recordPosting(LedgerEntryType type, Money newOpenBalance, Money newPaidTotal,
                                     Money newCreditorPaidTotal, Instant newLastPaymentAt) {
        requireAcceptsPosting(type);
        Guard.require(newOpenBalance != null && !newOpenBalance.isNegative(), "title.balance_negative",
                "Saldo do título não pode ficar negativo");
        Guard.require(newPaidTotal != null && !newPaidTotal.isNegative(), "title.paid_negative",
                "Total pago não pode ficar negativo");
        Guard.require(newCreditorPaidTotal != null && !newCreditorPaidTotal.isNegative()
                        && !newCreditorPaidTotal.isGreaterThan(newPaidTotal), "title.creditor_paid_invalid",
                "Total pago ao credor deve ficar entre zero e o total pago");
        TitleStatus previous = status;
        this.openBalance = newOpenBalance;
        this.paidTotal = newPaidTotal;
        this.creditorPaidTotal = newCreditorPaidTotal;
        this.lastPaymentAt = newLastPaymentAt;
        if (newOpenBalance.isZero() && status.acceptsPostings() && type != LedgerEntryType.OPENING) {
            moveTo(TitleStatus.SETTLED, null);
        } else if (newOpenBalance.isPositive() && status == TitleStatus.SETTLED) {
            moveTo(TitleStatus.OPEN, null);
        }
        return previous;
    }

    /**
     * Estorno que deixou saldo num título quitado de titular eliminado (decisão 1603): o título fica quitado, com o
     * saldo registrado, e não volta à cobrança (voltaria a correr prazo e a entrar em fila sem titular).
     */
    public void keepSettledAfterErasure() {
        Guard.requireState(status == TitleStatus.OPEN && openBalance.isPositive(), "title.not_reopened",
                "Só o título reaberto pelo estorno fica quitado");
        moveTo(TitleStatus.SETTLED, ERASED_HOLDER_REASON);
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
        // o que a carga fez vale só para a situação em que foi gravado (H07 2b): toda mudança de status o limpa, e o
        // caminho da carga o grava de novo depois da transição
        this.settledByImportBatchId = null;
        this.settledByImportAt = null;
        this.suspensionReasonCode = null;
        this.suspensionImportBatchId = null;
        this.suspendedAt = null;
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

    /**
     * Valor gravado (pode ser a forma anonimizada): só para busca e persistência dentro do debt. Tela, texto e view usam
     * {@link #displayExternalId()} ({@code ArchitectureTest}).
     */
    public String getExternalId() {
        return externalId;
    }

    /** Identificador para exibição: a forma mascarada guardada quando trocado (decisão 1476), senão o gravado. */
    public String displayExternalId() {
        return externalIdDisplay != null ? externalIdDisplay : externalId;
    }

    /**
     * Troca o identificador pela forma anonimizada e guarda a forma mascarada para exibição (decisão 1476), na
     * anonimização do titular (ou no preenchimento dos já anonimizados). Uma vez só: o valor trocado não volta nem muda
     * (também recusado pelo gatilho do banco, que exige o devedor anonimizado).
     *
     * @return {@code false} se já estava trocado
     */
    public boolean anonymizeExternalId(String anonymized, String display) {
        if (ExternalIdDigest.isAnonymized(externalId)) {
            return false;
        }
        Guard.require(ExternalIdDigest.isAnonymized(anonymized), "title.external_id_anonymized_invalid",
                "Forma anonimizada do identificador inválida");
        Guard.require(display != null && !display.isBlank(), "title.external_id_display_required",
                "Forma de exibição do identificador obrigatória");
        this.externalId = anonymized;
        this.externalIdDisplay = display;
        return true;
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

    public Money getCreditorPaidTotal() {
        return creditorPaidTotal;
    }

    public Instant getLastPaymentAt() {
        return lastPaymentAt;
    }

    public boolean isManualPrincipal() {
        return manualPrincipal;
    }

    public Money getCreditorPrincipal() {
        return creditorPrincipal;
    }

    public Money getAcknowledgedPrincipal() {
        return acknowledgedPrincipal;
    }

    public UUID getSettledByImportBatchId() {
        return settledByImportBatchId;
    }

    public Instant getSettledByImportAt() {
        return settledByImportAt;
    }

    public TitleSuspensionReason getSuspensionReasonCode() {
        return suspensionReasonCode;
    }

    public UUID getSuspensionImportBatchId() {
        return suspensionImportBatchId;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }
}
