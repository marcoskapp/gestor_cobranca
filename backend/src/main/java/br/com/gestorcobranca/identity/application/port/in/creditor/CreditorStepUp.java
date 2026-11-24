package br.com.gestorcobranca.identity.application.port.in.creditor;

import java.time.Instant;

/**
 * Nova verificação do MFA da pessoa do credor <b>na própria requisição da ação</b> (aceite da prestação de contas,
 * pagamento direto): o código vai no corpo e é conferido na mesma transação, antes de qualquer efeito. Não existe
 * "sessão elevada" reutilizável. Código já usado (mesmo passo) é recusado; as falhas somam ao bloqueio da conta e, no
 * limite, a sessão é revogada.
 * <p>
 * Quem chama não pode ter travado nem alterado a linha da conta do credor ({@code identity.creditor_user}) antes, na
 * mesma transação: a falha é gravada em outra transação, que esperaria pela trava da primeira.
 */
public interface CreditorStepUp {

    /**
     * @param purpose ação verificada (vai para o log de acesso), ex.: {@code statement_acknowledge}
     * @return instante da verificação (o módulo dono grava junto da ação)
     * @throws br.com.gestorcobranca.shared.domain.BusinessRuleException código inválido ou repetido
     */
    Instant verify(String code, String purpose);
}
