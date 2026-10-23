package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Linha válida, com valores convertidos e prontos para gravação. Contatos seguem crus para o
 * módulo debt, que os normaliza e valida (contato inválido volta como aviso).
 */
public record ParsedRow(long rowNumber, Document document, String name, LocalDate birthDate,
                        List<ParsedContact> contacts, ParsedContract contract, String titleExternalId,
                        Integer installmentNumber, LocalDate dueDate, BigDecimal principalAmount,
                        BigDecimal reportedBalance, LocalDate balanceReferenceDate) {

    public ParsedRow {
        contacts = List.copyOf(contacts);
    }

    public enum ContactKind { PHONE, EMAIL, ADDRESS }

    public record ParsedContact(ContactKind kind, String value, boolean whatsapp) {
    }

    public record ParsedContract(String number, String product, LocalDate signedAt, BigDecimal originalAmount) {
    }
}
