package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Linha válida, com valores convertidos e prontos para gravação. Contatos seguem crus para o
 * módulo debt, que os normaliza e valida (contato inválido volta como aviso). Sem situação
 * informada, o título é considerado {@link TitleSituation#ACTIVE}.
 */
public record ParsedRow(long rowNumber, Document document, String name, LocalDate birthDate,
                        List<ParsedContact> contacts, ParsedContract contract, String titleExternalId,
                        Integer installmentNumber, LocalDate dueDate, BigDecimal principalAmount,
                        BigDecimal reportedBalance, LocalDate balanceReferenceDate, TitleSituation situation,
                        ParsedLegal legal, String tradeName, List<ParsedRepresentative> representatives) {

    public ParsedRow {
        contacts = List.copyOf(contacts);
        situation = situation == null ? TitleSituation.ACTIVE : situation;
        legal = legal == null ? new ParsedLegal(null, List.of(), null) : legal;
        representatives = representatives == null ? List.of() : List.copyOf(representatives);
    }

    public enum ContactKind { PHONE, EMAIL, ADDRESS }

    /**
     * @param personName nome da pessoa de contato (B2, opcional)
     * @param personRole papel no código do debt (FINANCE, ACCOUNTS_PAYABLE, OWNER_PARTNER, LEGAL, OTHER), opcional
     */
    public record ParsedContact(ContactKind kind, String value, boolean whatsapp, String personName, String personRole) {

        public ParsedContact(ContactKind kind, String value, boolean whatsapp) {
            this(kind, value, whatsapp, null, null);
        }
    }

    /**
     * Representante da empresa (B3), só em linha de CNPJ.
     *
     * @param role         código do debt (MANAGING_PARTNER, ATTORNEY_IN_FACT, OTHER); nulo = coluna não informada
     *                     (mantém o cadastro; na criação vale OTHER)
     * @param canNegotiate poder para negociar; nulo = coluna não informada (mantém o cadastro; na criação vale falso)
     */
    public record ParsedRepresentative(Document document, String name, String role, Boolean canNegotiate) {
    }

    public record ParsedContract(String number, String product, LocalDate signedAt, BigDecimal originalAmount) {
    }
}
