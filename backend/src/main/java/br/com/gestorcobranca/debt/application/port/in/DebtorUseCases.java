package br.com.gestorcobranca.debt.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
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

    /** @param document documento formatado (000.000.000-00 / 00.000.000/0000-00) */
    record RevealedDocument(UUID debtorId, String documentType, String document) {
    }
}
