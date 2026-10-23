package br.com.gestorcobranca.debt.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cadastro de devedores (M02, RB01). Um devedor é visível ao ator somente se tiver ao menos um
 * título num credor do seu escopo (atores com acesso a todos os credores veem também devedores
 * ainda sem títulos). O documento é sempre exibido mascarado; a revelação exige permissão
 * específica e é registrada no log de acesso.
 */
public interface DebtorUseCases {

    /** Cadastro manual. Documento já cadastrado no tenant → conflito {@code debtor.document_taken}. */
    DebtorView register(RegisterDebtorCommand command);

    DebtorView update(UUID debtorId, UpdateDebtorCommand command);

    DebtorView get(UUID debtorId);

    /**
     * Nomes de exibição de vários devedores de uma vez (agenda, listagens de outros módulos), sem
     * documento nem contatos. Devedores inexistentes ou fora do escopo do ator são omitidos (sem
     * erro, para não revelar existência). Devedor anonimizado aparece com o nome substituto.
     *
     * @param debtorIds até {@value #MAX_NAMES} ids
     */
    List<DebtorName> names(Collection<UUID> debtorIds);

    /** Limite de ids por chamada de {@link #names(Collection)}. */
    int MAX_NAMES = 200;

    /** Documento completo. Exige {@code DEBTOR_SENSITIVE_VIEW}; registra {@code SENSITIVE_DATA_VIEWED}. */
    RevealedDocument revealDocument(UUID debtorId);

    /** Busca exata por CPF/CNPJ (com ou sem máscara) pelo blind index; vazio se inexistente ou fora do escopo. */
    Optional<DebtorView> findByDocument(String document);

    /**
     * @param text trecho do nome ou CPF/CNPJ completo (opcional)
     */
    PageResponse<DebtorView> search(String text, UUID creditorId, UUID portfolioId, PageQuery page);

    /**
     * Elimina os dados pessoais do devedor e seus contatos (LGPD), preservando títulos e razão.
     * Uso pelo módulo compliance no atendimento de solicitação do titular ou na política de
     * retenção. Exige {@code DATA_SUBJECT_REQUEST_MANAGE} e escopo sobre todos os credores do
     * devedor; recusado enquanto houver título em cobrança ({@code debtor.has_open_titles}).
     */
    DebtorView anonymize(UUID debtorId, String reason);

    /**
     * <strong>Uso interno do módulo compliance (retenção); não exposto via web.</strong>
     * Devedores ativos (não anonimizados) sem títulos em cobrança (OPEN/SUSPENDED) cuja última
     * movimentação — alteração do título mais recente, ou o cadastro para devedores sem títulos —
     * é anterior a {@code closedBefore}. Paginação por chave ({@code id > afterId}, em ordem de id)
     * para que execuções simuladas ou itens mantidos não sejam revisitados no mesmo processamento.
     * Exige {@code RETENTION_MANAGE} e acesso a todos os credores.
     *
     * @param afterId último id do lote anterior; nulo para começar do início
     * @param limit   tamanho do lote (1 a 1000)
     */
    List<InactiveDebtor> findInactiveDebtors(Instant closedBefore, UUID afterId, int limit);

    /**
     * @param lastActivityAt última movimentação considerada
     * @param creditorIds    credores em que o devedor tem títulos (para a base legal de cada um)
     */
    record InactiveDebtor(UUID debtorId, Instant lastActivityAt, List<UUID> creditorIds) {

        public InactiveDebtor {
            creditorIds = List.copyOf(creditorIds);
        }
    }

    record RegisterDebtorCommand(String document, String name, LocalDate birthDate, String notes) {
    }

    record UpdateDebtorCommand(String name, LocalDate birthDate, String notes) {
    }

    /**
     * @param personType     INDIVIDUAL ou COMPANY
     * @param maskedDocument documento mascarado; nulo após anonimização
     * @param status         ACTIVE ou ANONYMIZED
     */
    record DebtorView(UUID id, String personType, String name, String maskedDocument, LocalDate birthDate,
                      String status, String notes, Instant createdAt, Instant anonymizedAt) {
    }

    /** @param status ACTIVE ou ANONYMIZED */
    record DebtorName(UUID id, String name, String status) {
    }

    /** @param document documento formatado (000.000.000-00 / 00.000.000/0000-00) */
    record RevealedDocument(UUID debtorId, String documentType, String document) {
    }
}
