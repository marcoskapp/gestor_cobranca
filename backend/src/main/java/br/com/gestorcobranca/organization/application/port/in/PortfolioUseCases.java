package br.com.gestorcobranca.organization.application.port.in;

import br.com.gestorcobranca.shared.web.PageQuery;
import br.com.gestorcobranca.shared.web.PageResponse;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Carteiras: cadastro, vigência e ciclo de vida (F03, M02). */
public interface PortfolioUseCases {

    PortfolioView create(CreatePortfolioCommand command);

    PortfolioView update(UUID portfolioId, UpdatePortfolioCommand command);

    /**
     * Ativa, suspende, devolve ou encerra a carteira. Publica {@code PortfolioStatusChanged}.
     *
     * @param targetStatus ACTIVE, SUSPENDED, RETURNED ou CLOSED
     */
    PortfolioView changeStatus(UUID portfolioId, String targetStatus, String reason);

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
     * @param operable ativa e dentro da vigência na data de hoje (RB02)
     */
    record PortfolioView(UUID id, UUID creditorId, UUID contractId, String code, String name, String description,
                         LocalDate receivedOn, LocalDate validUntil, String status, String statusReason,
                         boolean operable) {
    }
}
