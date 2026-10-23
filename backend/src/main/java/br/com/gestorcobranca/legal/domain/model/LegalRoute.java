package br.com.gestorcobranca.legal.domain.model;

/**
 * Vias de recuperação avaliadas na triagem (docs/design/juridico.md §B.1; produto §2, etapa 2).
 * Extrajudiciais: protesto, negativação e execução extrajudicial da garantia (consolidação da
 * propriedade fiduciária de imóvel — Lei 9.514/1997; execução extrajudicial de hipoteca e de
 * alienação fiduciária de veículo — Lei 14.711/2023). Judiciais: execução de título extrajudicial,
 * monitória, ação de cobrança e busca e apreensão (DL 911/1969).
 */
public enum LegalRoute {
    PROTEST(false),
    CREDIT_BUREAU_LISTING(false),
    EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT(false),
    EXECUTION(true),
    MONITORY_ACTION(true),
    COLLECTION_ACTION(true),
    SEARCH_AND_SEIZURE(true);

    private final boolean judicial;

    LegalRoute(boolean judicial) {
        this.judicial = judicial;
    }

    public boolean isJudicial() {
        return judicial;
    }
}
