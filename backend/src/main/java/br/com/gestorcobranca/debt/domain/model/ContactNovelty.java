package br.com.gestorcobranca.debt.domain.model;

/**
 * Situação do selo "Novo" de um contato recebido numa carga para devedor que já existia
 * (docs/tecnico/desenho/contatos-recebidos.md §1). Fora da janela, inválido ou nunca novo: sem selo.
 */
public enum ContactNovelty {
    /** Ainda sem tentativa por este contato. */
    NEW,
    /** Já testado, com contato efetivo ("Novo · atendeu"). */
    NEW_ANSWERED,
    /** Já testado, sem contato efetivo ("Novo · sem resposta"). */
    NEW_NO_ANSWER
}
