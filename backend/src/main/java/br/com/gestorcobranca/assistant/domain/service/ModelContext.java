package br.com.gestorcobranca.assistant.domain.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Contexto <strong>minimizado</strong> enviado ao modelo a cada chamada (esquema
 * {@code AssistantTurn.CONTEXT_SCHEMA_VERSION}). Contém apenas o necessário para conversar: primeiro
 * nome, estado da identidade, dias em atraso, saldo e ofertas devolvidos pelo motor (com referências
 * opacas {@code O1}, {@code P1}, {@code I1}) e as regras da política. Nunca documento, contatos, nome completo,
 * ids internos, dados de terceiros nem o envelope de negociação da política.
 *
 * @param identity  PENDING (pedir 3 primeiros dígitos do CPF e data de nascimento; nada da dívida pode ser
 *                  revelado), VERIFIED, FAILED, NOT_REQUIRED ou OPERATOR_MEDIATED (rascunho: o operador confere)
 * @param attemptsLeft tentativas de confirmação restantes
 */
public record ModelContext(String assistantName, String tone, String mode, String channel, LocalDate today,
                           String firstName, String identity, int attemptsLeft, int turn, int maxTurns,
                           CaseInfo collectionCase, DebtInfo debt, List<OfferInfo> offers, ProposalInfo proposal,
                           List<InstallmentInfo> installments, PixInfo pix, List<String> allowedTopics,
                           List<String> rules) {

    public ModelContext {
        offers = offers == null ? List.of() : List.copyOf(offers);
        installments = installments == null ? List.of() : List.copyOf(installments);
        allowedTopics = allowedTopics == null ? List.of() : List.copyOf(allowedTopics);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** @param stage etapa do caso (OPEN, IN_NEGOTIATION, PROMISE…) */
    public record CaseInfo(long daysOverdue, String stage) {
    }

    public record DebtInfo(BigDecimal updatedTotal, int titles, LocalDate referenceDate) {
    }

    /** Oferta calculada pelo motor (o modelo cita estes valores exatamente). */
    public record OfferInfo(String ref, int level, int installments, BigDecimal total, BigDecimal downPayment,
                            BigDecimal installmentAmount, LocalDate firstDueDate, BigDecimal discountTotal,
                            boolean withinAuthority) {
    }

    public record ProposalInfo(String ref, String status, int installments, BigDecimal total) {
    }

    /** Parcela em aberto de acordo vigente (referência opaca {@code I1}…; valor = saldo da parcela). */
    public record InstallmentInfo(String ref, int number, LocalDate dueDate, BigDecimal amount, String status) {
    }

    /** @param enabled a política permite o Pix na conversa (a elegibilidade é decidida pelo servidor) */
    public record PixInfo(boolean enabled) {
    }
}
