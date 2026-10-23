package br.com.gestorcobranca.legal.domain.model;

/**
 * Controle das despesas contra o orçamento aprovado pelo credor: {@code BLOCK} (padrão) recusa
 * despesa sem autorização vigente ou acima do orçamento; {@code WARN} aceita e sinaliza.
 */
public enum BudgetEnforcement {
    BLOCK,
    WARN
}
