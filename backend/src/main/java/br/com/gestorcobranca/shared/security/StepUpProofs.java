package br.com.gestorcobranca.shared.security;

import java.time.Instant;

/**
 * Conferência, pelo módulo dono da ação, do comprovante da nova verificação do MFA (SEG-F07, decisão 1436): o
 * comprovante é opaco, emitido pelo {@code identity} na verificação do código, vale só na mesma transação, para a mesma
 * pessoa, sessão, finalidade e alvo, e uma única vez. O dono nunca recebe um instante informado pelo chamador. Só o
 * {@code identity} implementa ({@code ArchitectureTest}).
 */
public interface StepUpProofs {

    /**
     * @param subjectRef o alvo da ação (ex.: id do demonstrativo; para o pagamento, o resumo do que foi informado)
     * @return instante da verificação, para gravar junto da ação
     * @throws br.com.gestorcobranca.shared.domain.BusinessRuleException comprovante ausente, de outra transação, pessoa,
     *                                                                   sessão, finalidade ou alvo, ou já usado
     */
    Instant consume(String proof, StepUpPurpose purpose, String subjectRef);
}
