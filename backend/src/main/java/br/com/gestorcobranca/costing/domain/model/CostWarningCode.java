package br.com.gestorcobranca.costing.domain.model;

/**
 * Avisos da apuração, mostrados no painel com a quantidade ({@code count}) e gravados por dia × credor × carteira para
 * respeitar o escopo por credor.
 */
public enum CostWarningCode {
    /** Horas (diretas ou ociosas) de pessoas sem faixa de custo vigente no dia: entram com custo zero. */
    HOURS_WITHOUT_BAND,
    /** Meses sem câmbio informado em que o custo de IA usou o último câmbio anterior. */
    MISSING_FX_RATE,
    /** Meses sem nenhum câmbio informado até então: o custo de IA ficou em zero. */
    NO_FX_RATE,
    /** Unidades (minutos, conversas, mensagens) sem tarifa vigente: entram com custo zero. */
    MISSING_TARIFF,
    /** Custos fixos sem base de rateio no mês (nenhuma hora, recuperado ou caso): ficam sem credor. */
    OVERHEAD_WITHOUT_BASIS,
    /**
     * Faturas de canal de um mês sem nenhum uso estimado do item (nem valor, nem quantidade): o valor da fatura fica
     * sem credor no primeiro dia do mês (só na base conciliada).
     */
    INVOICE_WITHOUT_USAGE
}
