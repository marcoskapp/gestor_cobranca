package br.com.gestorcobranca.debt.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Representantes da empresa devedora (cobrança B2B, B3): quem pode negociar em nome dela. O representante não é devedor
 * (não entra em listas, buscas, filas, segmentos, score ou réguas). Só devedor pessoa jurídica tem representantes; o
 * representante é sempre pessoa física (CPF), único por empresa enquanto vigente ({@code representative.duplicate},
 * 409). O CPF é exibido mascarado; a revelação exige {@code DEBTOR_SENSITIVE_VIEW} e registra
 * {@code SENSITIVE_DATA_VIEWED}. Toda alteração é auditada (nome mascarado, CPF mascarado).
 * <p>
 * Todas as operações exigem a empresa visível ao ator (escopo por credor do devedor); um representante de outra empresa
 * é tratado como inexistente.
 */
public interface CompanyRepresentativeUseCases {

    /** Representantes da empresa (vigentes primeiro), sem documento em claro. */
    List<RepresentativeView> list(UUID companyId);

    /** Exige {@code DEBTOR_MANAGE}. */
    RepresentativeView add(UUID companyId, AddRepresentativeCommand command);

    /** Exige {@code DEBTOR_MANAGE}; o CPF não muda (outro CPF é outro representante). */
    RepresentativeView update(UUID companyId, UUID representativeId, UpdateRepresentativeCommand command);

    /** Encerramento com motivo (exige {@code DEBTOR_MANAGE}); o registro continua na ficha como encerrado. */
    RepresentativeView end(UUID companyId, UUID representativeId, String reason);

    /** CPF completo. Exige {@code DEBTOR_SENSITIVE_VIEW}; registra {@code SENSITIVE_DATA_VIEWED}. */
    RevealedRepresentative revealDocument(UUID companyId, UUID representativeId);

    /**
     * <strong>Uso interno de outros módulos (portal); não exposto via web.</strong> Representante vigente, dentro da
     * validade e com poder para negociar hoje, com o CPF informado — conferido pelo blind index do CPF (finalidade
     * própria, calculada aqui). Vazio se não houver (sem dizer o motivo). O chamador nunca registra o CPF em log, evento,
     * métrica ou auditoria.
     */
    Optional<RepresentativeView> negotiator(UUID companyId, String cpf);

    /** <strong>Uso interno (portal).</strong> A empresa tem ao menos um representante que pode negociar hoje. */
    boolean hasNegotiators(UUID companyId);

    /** <strong>Uso interno (portal, negotiation).</strong> Representante da empresa (404 se de outra empresa). */
    RepresentativeView get(UUID companyId, UUID representativeId);

    /**
     * @param role          MANAGING_PARTNER, ATTORNEY_IN_FACT ou OTHER
     * @param validUntil    validade do poder (opcional; sem ela, vale até o encerramento)
     * @param evidenceNote  texto curto da evidência ("contrato social de 2024"), até 200 caracteres
     */
    record AddRepresentativeCommand(String document, String name, String role, boolean canNegotiate,
                                    LocalDate validUntil, String evidenceNote) {
    }

    record UpdateRepresentativeCommand(String name, String role, boolean canNegotiate, LocalDate validUntil,
                                       String evidenceNote) {
    }

    /**
     * @param maskedDocument  CPF mascarado ({@code ***.456.789-**})
     * @param negotiatesToday vigente, dentro da validade e com poder para negociar hoje
     * @param source          MANUAL ou IMPORT
     */
    record RepresentativeView(UUID id, UUID companyId, String name, String maskedDocument, String role,
                              boolean canNegotiate, LocalDate validUntil, String evidenceNote, String source,
                              boolean active, boolean negotiatesToday, Instant createdAt, Instant endedAt,
                              String endReason) {
    }

    /** @param document CPF formatado (000.000.000-00) */
    record RevealedRepresentative(UUID representativeId, UUID companyId, String document) {
    }
}
