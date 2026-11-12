package br.com.gestorcobranca.organization.application.port.in;

import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Carteiras: cadastro, vigência e ciclo de vida (F03, M02). */
public interface PortfolioUseCases {

    PortfolioView create(CreatePortfolioCommand command);

    /**
     * @param expectedVersion versão lida pelo chamador ({@code If-Match} da API pública); diferente da atual →
     *                        {@code PreconditionFailedException}; nulo = sem verificação
     */
    PortfolioView update(UUID portfolioId, UpdatePortfolioCommand command, Long expectedVersion);

    /**
     * Ativa, suspende, devolve ou encerra a carteira. Publica {@code PortfolioStatusChanged}.
     *
     * @param targetStatus    ACTIVE, SUSPENDED, RETURNED ou CLOSED
     * @param expectedVersion versão lida pelo chamador ({@code If-Match}); nulo = sem verificação
     */
    PortfolioView changeStatus(UUID portfolioId, String targetStatus, String reason, Long expectedVersion);

    /**
     * Muda o regime da carteira (cobrança B2B §6), com motivo e auditoria. Não altera contrato nem política; muda só as
     * regras de proteção que dependem do regime (alerta de multa do CDC na revisão da carga, superendividamento).
     *
     * @param regime          CONSUMER ou BUSINESS
     * @param expectedVersion versão lida pelo chamador; nulo = sem verificação
     */
    PortfolioView changeRegime(UUID portfolioId, String regime, String reason, Long expectedVersion);

    PortfolioView get(UUID portfolioId);

    /**
     * Para regras de proteção de outros módulos (superendividamento no compliance): alguma das carteiras informadas
     * está no regime de consumo? Lista vazia ou carteira desconhecida contam como consumo (proteção nunca retirada por
     * inferência). Sem filtro de escopo de propósito: o devedor pode ter títulos em credores fora do escopo do ator, e a
     * proteção do consumidor não pode depender de quem pergunta; a resposta é só um booleano, sem dado da carteira.
     */
    boolean consumerProtectionApplies(Collection<UUID> portfolioIds);

    /** Para outros módulos: carteira visível ao ator e que aceita importação. */
    PortfolioView requireAcceptingImports(UUID portfolioId);

    List<PortfolioView> getAll(Collection<UUID> portfolioIds);

    PageResponse<PortfolioView> search(UUID creditorId, String status, String text, PageQuery page);

    /** Sincronização incremental (API pública): carteiras do credor alteradas na janela, paginadas por cursor. */
    ChangePage<PortfolioView> changes(UUID creditorId, ChangeQuery query);

    /** @param regime CONSUMER ou BUSINESS; nulo = CONSUMER (padrão protetivo) */
    record CreatePortfolioCommand(UUID creditorId, UUID contractId, String code, String name, String description,
                                  LocalDate receivedOn, LocalDate validUntil, String regime) {

        /** Carteira no regime de consumo, o padrão (API pública v1, carga de demonstração e testes). */
        public CreatePortfolioCommand(UUID creditorId, UUID contractId, String code, String name, String description,
                                      LocalDate receivedOn, LocalDate validUntil) {
            this(creditorId, contractId, code, name, description, receivedOn, validUntil, null);
        }
    }

    record UpdatePortfolioCommand(String name, String description, LocalDate receivedOn, LocalDate validUntil) {
    }

    /**
     * @param operable  ativa e dentro da vigência na data de hoje (RB02)
     * @param regime    CONSUMER ou BUSINESS (cobrança B2B §6)
     * @param version   versão do registro (lock otimista) — {@code ETag} da API pública
     * @param updatedAt última alteração
     */
    record PortfolioView(UUID id, UUID creditorId, UUID contractId, String code, String name, String description,
                         LocalDate receivedOn, LocalDate validUntil, String status, String statusReason,
                         boolean operable, String regime, long version, java.time.Instant updatedAt) {
    }
}
