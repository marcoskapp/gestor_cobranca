package br.com.gestorcobranca.costing.domain.model;

/**
 * Categorias dos fatos de custo (desenho §4). A margem de contribuição desconta só os custos diretos; a margem
 * líquida desconta também a ociosidade e o rateio dos custos fixos (decisões do responsável, §9 Q2 e Q3).
 */
@org.springframework.modulith.NamedInterface("usecases")
public enum CostCategory {
    HUMAN_DIRECT(true),
    HUMAN_IDLE(false),
    AI(true),
    TELEPHONY(true),
    MESSAGING(true),
    VARIABLE_COMMISSION(true),
    LEGAL(true),
    OVERHEAD(false);

    private final boolean direct;

    CostCategory(boolean direct) {
        this.direct = direct;
    }

    /** Entra no custo direto (margem de contribuição). */
    public boolean direct() {
        return direct;
    }
}
