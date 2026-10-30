package br.com.gestorcobranca.debt.application.port.in;

import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Consulta e gestão do ciclo de vida dos títulos (M02): suspensão, reativação, cancelamento,
 * devolução ao credor e transferência de carteira. Toda mudança preserva o histórico (RB01),
 * é auditada e publica {@code TitleStatusChanged}. Operações em lote são atômicas: se um título
 * não puder ser alterado, nenhum é.
 */
public interface DebtTitleUseCases {

    TitleView get(UUID titleId);

    /** Título pelo identificador do credor ({@code id_titulo} da carga); vazio se inexistente ou fora do escopo. */
    java.util.Optional<TitleView> findByExternalId(UUID creditorId, String externalId);

    /** Títulos do devedor em credores do escopo do ator. */
    List<TitleView> listByDebtor(UUID debtorId);

    PageResponse<TitleView> search(TitleSearch filter, PageQuery page);

    /** Saldo por componente calculado do razão e saldos informados pelo credor nas cargas. */
    TitleBalanceView balance(UUID titleId);

    LedgerPage ledger(UUID titleId, PageQuery page);

    /** Períodos de titularidade (credor/carteira) do título, do mais antigo ao atual. */
    List<OwnershipView> ownershipHistory(UUID titleId);

    /**
     * Sincronização incremental (API pública): títulos dos credores do escopo alterados na janela, em ordem de
     * atualização e id, paginados por cursor.
     *
     * @param creditorId  credor (obrigatório; precisa estar no escopo)
     * @param portfolioId carteira do credor (opcional)
     */
    ChangePage<TitleView> changes(UUID creditorId, UUID portfolioId, ChangeQuery query);

    default TitleView suspend(UUID titleId, String reason) {
        return suspend(titleId, reason, null);
    }

    /**
     * @param expectedVersion versão que o chamador leu (concorrência otimista, {@code If-Match}); diferente da atual →
     *                        {@code PreconditionFailedException}; nulo = sem verificação
     */
    TitleView suspend(UUID titleId, String reason, Long expectedVersion);

    default TitleView reactivate(UUID titleId) {
        return reactivate(titleId, null);
    }

    TitleView reactivate(UUID titleId, Long expectedVersion);

    default TitleView cancel(UUID titleId, String reason) {
        return cancel(titleId, reason, null);
    }

    TitleView cancel(UUID titleId, String reason, Long expectedVersion);

    /** Devolve os títulos ao credor e encerra a titularidade. */
    List<TitleView> returnToCreditor(Collection<UUID> titleIds, String reason);

    /** Devolve um título ao credor, com verificação opcional da versão lida ({@code If-Match}). */
    TitleView returnToCreditor(UUID titleId, String reason, Long expectedVersion);

    /** Move os títulos para outra carteira do mesmo credor, encerrando e abrindo titularidade. */
    List<TitleView> transferPortfolio(Collection<UUID> titleIds, UUID targetPortfolioId, String reason);

    /**
     * <strong>Uso interno do módulo collection; não exposto via web.</strong> Devedores com títulos
     * gravados ou atualizados pelo lote de importação (abertura e atualização de casos após
     * {@code PortfolioLoaded}). Paginação por chave ({@code debtorId > afterDebtorId}, em ordem de
     * id). Exige o credor no escopo do ator.
     *
     * @param afterDebtorId último devedor do lote anterior; nulo para começar do início
     * @param limit         tamanho do lote (1 a 1000)
     */
    List<UUID> debtorIdsByImportBatch(UUID creditorId, UUID importBatchId, UUID afterDebtorId, int limit);

    /**
     * Filtros (todos opcionais). Os filtros de atraso consideram apenas títulos em cobrança
     * (OPEN ou SUSPENDED) e a data de hoje no fuso de operação.
     *
     * @param status OPEN, SETTLED, CANCELLED, SUSPENDED ou RETURNED
     */
    record TitleSearch(UUID creditorId, UUID portfolioId, UUID debtorId, String status, LocalDate dueFrom,
                       LocalDate dueTo, Integer minOverdueDays, Integer maxOverdueDays) {
    }

    /**
     * @param version   versão do registro (lock otimista) — {@code ETag} da API pública
     * @param updatedAt última alteração do título
     */
    record TitleView(UUID id, UUID creditorId, UUID portfolioId, UUID debtorId, UUID originContractId,
                     String externalId, Integer installmentNumber, LocalDate dueDate, BigDecimal principalAmount,
                     BigDecimal openBalance, BigDecimal paidTotal, String status, String statusReason,
                     boolean disputeBlocked, UUID disputeId, Instant lastPaymentAt, long daysOverdue,
                     UUID importBatchId, long version, Instant updatedAt) {
    }

    /**
     * @param component  PRINCIPAL, INTEREST, FINE, FEES ou HONORARIUM
     * @param charged    abertura, encargos e ajustes
     * @param discounted descontos e baixas
     * @param paid       pagamentos brutos
     * @param reversed   estornos
     * @param balance    saldo do componente
     */
    record ComponentBalanceView(String component, BigDecimal charged, BigDecimal discounted, BigDecimal paid,
                                BigDecimal reversed, BigDecimal balance) {
    }

    record BalanceSnapshotView(LocalDate referenceDate, BigDecimal reportedBalance, UUID importBatchId,
                               Instant recordedAt) {
    }

    /**
     * @param divergence saldo do razão menos o último saldo informado pelo credor (nulo sem informação)
     * @param snapshots  saldos informados mais recentes (até 12)
     */
    record TitleBalanceView(UUID titleId, String status, List<ComponentBalanceView> components, BigDecimal openBalance,
                            BigDecimal paidTotal, Instant lastPaymentAt, BigDecimal divergence,
                            List<BalanceSnapshotView> snapshots) {
    }

    /**
     * @param amount valor com sinal: positivo aumenta o saldo (abertura, encargo, estorno),
     *               negativo reduz (pagamento, desconto, baixa)
     */
    record LedgerEntryView(UUID id, String entryType, String component, BigDecimal amount, Instant occurredAt,
                           String referenceType, UUID referenceId, String reason, Instant recordedAt) {
    }

    record LedgerPage(UUID titleId, PageResponse<LedgerEntryView> entries) {
    }

    /** @param releaseType TRANSFER ou RETURN; nulo no período atual */
    record OwnershipView(UUID creditorId, UUID portfolioId, Instant assignedAt, Instant releasedAt, String releaseType,
                         String releaseReason) {
    }
}
