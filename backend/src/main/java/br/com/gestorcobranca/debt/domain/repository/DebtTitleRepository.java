package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DebtTitleRepository {

    DebtTitle save(DebtTitle title);

    Optional<DebtTitle> findById(UUID id);

    /** Lock pessimista: serializa lançamentos e mudanças de status concorrentes no mesmo título. */
    Optional<DebtTitle> findByIdForUpdate(UUID id);

    /** Credor do título, sem carregar a entidade nem travar a linha (conferência de escopo antes de um lock). */
    Optional<UUID> findCreditorIdById(UUID id);

    /** Lock pessimista em lote, sempre na ordem do id (evita deadlock entre operações em lote). */
    List<DebtTitle> findAllByIdInForUpdate(Collection<UUID> ids);

    Optional<DebtTitle> findByCreditorIdAndExternalIdForUpdate(UUID creditorId, String externalId);

    /** @param allowedCreditorIds {@code null} = sem restrição */
    List<DebtTitle> findByDebtor(UUID debtorId, Collection<UUID> allowedCreditorIds);

    boolean existsByDebtorIdAndCreditorId(UUID debtorId, UUID creditorId);

    boolean existsByDebtorIdAndCreditorIdNot(UUID debtorId, UUID creditorId);

    /** Devedor tem títulos ainda em cobrança (abertos ou suspensos). */
    boolean existsOpenByDebtorId(UUID debtorId);

    /** Credores em que o devedor tem títulos (define a visibilidade do devedor por escopo). */
    List<UUID> findCreditorIdsByDebtorId(UUID debtorId);

    /** Carteiras em que o devedor tem títulos (regras de proteção que dependem do regime da carteira). */
    List<UUID> findPortfolioIdsByDebtorId(UUID debtorId);

    /** Dentre os devedores informados, os que têm títulos em algum dos credores (visibilidade em lote). */
    List<UUID> findDebtorIdsWithTitlesInCreditors(Collection<UUID> debtorIds, Collection<UUID> creditorIds);

    Page<DebtTitle> search(TitleSearchCriteria criteria, Pageable pageable);

    /**
     * Devedores dos títulos com o código exato no credor (busca rápida), até {@code limit}.
     *
     * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição
     */
    List<UUID> findDebtorIdsByExternalId(String externalId, Collection<UUID> allowedCreditorIds, int limit);

    /** Pares devedor × credor dos títulos dos devedores informados (credores de vários devedores numa consulta). */
    List<DebtorCreditor> findDebtorCreditors(Collection<UUID> debtorIds);

    /** Devedores distintos com títulos do credor marcados pelo lote, em ordem de id, após {@code afterDebtorId}. */
    List<UUID> findDebtorIdsByImportBatch(UUID creditorId, UUID importBatchId, UUID afterDebtorId, int limit);

    List<DebtTitle> findAllByIdIn(Collection<UUID> ids);

    /** Títulos (parcelas) de um contrato de origem, por vencimento. */
    List<DebtTitle> findByOriginContractIdOrderByDueDateAsc(UUID originContractId);

    List<DebtTitle> findByOriginContractIdInOrderByDueDateAsc(Collection<UUID> originContractIds);

    List<DebtTitle> findByCreditorIdAndExternalIdIn(UUID creditorId, Collection<String> externalIds);

    /**
     * Leitura incremental (sincronização): até {@code limit + 1} títulos do credor alterados na janela, depois do
     * cursor, em ordem de atualização e id.
     */
    List<DebtTitle> findChanges(UUID creditorId, UUID portfolioId, ChangeQuery query);
}
