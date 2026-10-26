package br.com.gestorcobranca.shared.ai;

/**
 * Nível de modelo de uma chamada ao provedor, neutro de provedor: o adaptador o traduz para o modelo ou
 * perfil de inferência configurado pelo produto (no Amazon Bedrock, {@code STANDARD} = Claude Haiku e
 * {@code ADVANCED} = Claude Sonnet). Escolhido pelo servidor a cada chamada — o modelo não escolhe o próprio
 * nível (assistente: {@code ModelTierSelector}; chat de suporte: sempre {@code STANDARD}).
 */
public enum ModelTier {
    /** conversa geral, identificação, intenção e suporte (menor custo e latência) */
    STANDARD,
    /** negociação, proposta, aceite e Pix: onde erro custa dinheiro ou gera problema de compliance */
    ADVANCED;

    public ModelTier max(ModelTier other) {
        return other == null || compareTo(other) >= 0 ? this : other;
    }
}
