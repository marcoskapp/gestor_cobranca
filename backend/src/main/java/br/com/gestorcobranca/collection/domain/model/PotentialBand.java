package br.com.gestorcobranca.collection.domain.model;

/**
 * Faixa de potencial do caso na distribuição por nível de desempenho ({@code TIERED}): tercis dos casos disponíveis
 * na fila pelo valor esperado (probabilidade do score × saldo elegível). {@link #DEVELOPMENT} marca o caso de alto
 * potencial entregue pela fatia de desenvolvimento (rodízio entre todos os níveis), para que o histórico de
 * atribuição explique por que um caso alto foi a um nível que normalmente não o receberia.
 */
public enum PotentialBand {
    HIGH, MEDIUM, LOW, DEVELOPMENT;

    /**
     * Faixa como pode ser mostrada a quem não vê o nível de quem recebeu: {@link #DEVELOPMENT} vira {@link #HIGH}. No
     * "Próximo caso" só os níveis C e D recebem a vez de desenvolvimento, então a marca revelaria o nível (dado pessoal
     * do empregado). O banco guarda a marca original (auditável por quem vê o nível).
     */
    public PotentialBand withoutDevelopmentMark() {
        return this == DEVELOPMENT ? HIGH : this;
    }
}
