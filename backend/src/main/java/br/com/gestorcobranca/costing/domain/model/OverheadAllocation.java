package br.com.gestorcobranca.costing.domain.model;

/**
 * Regra de rateio de um custo fixo do mês (desenho §3.4; padrão {@link #HOURS}, decisão do responsável §9 Q3).
 */
@org.springframework.modulith.NamedInterface("usecases")
public enum OverheadAllocation {
    /** Pelas horas humanas (diretas + ociosas) de cada credor e carteira no mês. */
    HOURS,
    /** Pelo valor recuperado bruto (pagamentos, sem estornos) de cada credor e carteira no mês. */
    RECOVERED,
    /** Pelos casos trabalhados (caso × dia com interação) de cada credor e carteira no mês. */
    CASES,
    /** Inteiro a um credor (ex.: equipe dedicada), distribuído pelos dias do mês, sem carteira. */
    CREDITOR
}
