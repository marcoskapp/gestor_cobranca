package br.com.gestorcobranca.organization.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Carteiras: cadastro, vigência e ciclo de vida (F03, M02). */
public interface PortfolioUseCases {

    PortfolioView create(CreatePortfolioCommand command);

    default PortfolioView update(UUID portfolioId, UpdatePortfolioCommand command) {
        return update(portfolioId, command, null);
    }

    /**
     * @param expectedVersion versão lida pelo chamador ({@code If-Match} da API pública); diferente da atual →
     *                        {@code PreconditionFailedException}; nulo = sem verificação
     */
    PortfolioView update(UUID portfolioId, UpdatePortfolioCommand command, Long expectedVersion);

    default PortfolioView changeStatus(UUID portfolioId, String targetStatus, String reason) {
        return changeStatus(portfolioId, targetStatus, reason, null);
    }

    /**
     * Ativa, suspende, devolve ou encerra a carteira. Publica {@code PortfolioStatusChanged}.
     *
     * @param targetStatus    ACTIVE, SUSPENDED, RETURNED ou CLOSED
     * @param expectedVersion versão lida pelo chamador ({@code If-Match}); nulo = sem verificação
     */
    PortfolioView changeStatus(UUID portfolioId, String targetStatus, String reason, Long expectedVersion);

    PortfolioView get(UUID portfolioId);

    /** Para outros módulos: carteira visível ao ator e que aceita importação. */
    PortfolioView requireAcceptingImports(UUID portfolioId);

    List<PortfolioView> getAll(Collection<UUID> portfolioIds);

    PageResponse<PortfolioView> search(UUID creditorId, String status, String text, PageQuery page);

    record CreatePortfolioCommand(UUID creditorId, UUID contractId, String code, String name, String description,
                                  LocalDate receivedOn, LocalDate validUntil) {
    }

    record UpdatePortfolioCommand(String name, String description, LocalDate receivedOn, LocalDate validUntil) {
    }

    /**
     * @param operable  ativa e dentro da vigência na data de hoje (RB02)
     * @param version   versão do registro (lock otimista) — {@code ETag} da API pública
     * @param updatedAt última alteração
     */
    record PortfolioView(UUID id, UUID creditorId, UUID contractId, String code, String name, String description,
                         LocalDate receivedOn, LocalDate validUntil, String status, String statusReason,
                         boolean operable, long version, java.time.Instant updatedAt) {
    }
}
