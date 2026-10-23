package br.com.gestorcobranca.negotiation.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Geração do termo de acordo (PDF) com as condições aceitas pelo devedor. O conteúdo é armazenado
 * no {@code FileStorage} ({@code AGREEMENT_TERM}) e o SHA-256 vai para a evidência de aceite.
 */
public interface AgreementTermRenderer {

    byte[] render(TermDocument document);

    /**
     * @param debtorDocument documento do devedor mascarado
     * @param breakRule      texto da regra de quebra da política
     */
    record TermDocument(UUID proposalId, UUID replacedAgreementId, String creditorName, String debtorName,
                        String debtorDocument, List<TermTitle> titles, BigDecimal updatedAmount,
                        BigDecimal discountAmount, BigDecimal negotiatedAmount, BigDecimal financingInterest,
                        BigDecimal totalAmount, List<TermInstallment> schedule, String breakRule, int policyVersion,
                        String channel, Instant generatedAt) {
    }

    record TermTitle(String externalId, LocalDate dueDate, BigDecimal updatedAmount) {
    }

    record TermInstallment(int number, LocalDate dueDate, BigDecimal amount) {
    }
}
