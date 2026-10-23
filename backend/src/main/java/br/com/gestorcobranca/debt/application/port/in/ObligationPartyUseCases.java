package br.com.gestorcobranca.debt.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Partes da obrigação de um contrato de origem. O devedor principal é o do contrato (e dos
 * títulos) e aparece na listagem com papel PRINCIPAL; os coobrigados (avalista, fiador,
 * codevedor, terceiro garantidor) são o mesmo cadastro de devedor, consolidado pelo documento
 * (RB01): um documento ainda não cadastrado gera o devedor. Um coobrigado passa a ser visível a
 * quem tem escopo sobre o credor do contrato.
 */
public interface ObligationPartyUseCases {

    /** Devedor principal primeiro, depois os coobrigados (vigentes e encerrados). */
    List<PartyView> list(UUID contractId);

    /** Mesma pessoa no mesmo papel no contrato → conflito {@code obligation_party.duplicate}. */
    PartyView add(UUID contractId, AddPartyCommand command);

    /** Encerra a participação (ex.: exoneração da fiança); o registro permanece. */
    PartyView end(UUID contractId, UUID partyId, LocalDate endedOn, String reason);

    /**
     * @param document CPF/CNPJ (com ou sem máscara)
     * @param name     nome, usado apenas se a pessoa ainda não estiver cadastrada
     * @param role     GUARANTOR_AVAL, GUARANTOR_SURETY, CO_DEBTOR ou THIRD_PARTY_COLLATERAL_PROVIDER
     */
    record AddPartyCommand(String document, String name, LocalDate birthDate, String role, LocalDate sinceDate) {
    }

    /**
     * @param partyId        nulo para o devedor principal (derivado do contrato)
     * @param maskedDocument documento mascarado; nulo após anonimização
     */
    record PartyView(UUID partyId, UUID contractId, UUID debtorId, String name, String maskedDocument, String role,
                     LocalDate sinceDate, LocalDate endedOn, String endReason, boolean active) {
    }
}
