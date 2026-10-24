package br.com.gestorcobranca.debt.application.port.in;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Confirmação da identidade de quem fala pelo devedor num atendimento automatizado (assistente de
 * IA, F24), antes de revelar qualquer informação da dívida (CDC art. 42; LGPD). O documento nunca
 * sai do módulo: a comparação é feita aqui e só o resultado é devolvido. O devedor precisa ser
 * visível no escopo do ator.
 */
public interface DebtorIdentityUseCases {

    /**
     * @param documentPrefix primeiros 3 a 6 dígitos do CPF informados pelo interlocutor
     * @param birthDate      data de nascimento informada
     */
    IdentityCheck check(UUID debtorId, String documentPrefix, LocalDate birthDate);

    /**
     * @param outcome MATCHED, NOT_MATCHED ou UNAVAILABLE (cadastro sem data de nascimento,
     *                pessoa jurídica ou anonimizado: a confirmação depende de um humano)
     */
    record IdentityCheck(UUID debtorId, String outcome) {

        public boolean matched() {
            return "MATCHED".equals(outcome);
        }
    }
}
