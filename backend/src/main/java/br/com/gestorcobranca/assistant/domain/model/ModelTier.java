package br.com.gestorcobranca.assistant.domain.model;

/**
 * Nível de modelo de uma chamada ao provedor, neutro de provedor: o adaptador o traduz para o modelo ou
 * perfil de inferência configurado (no Amazon Bedrock, {@code STANDARD} = Claude Haiku e {@code ADVANCED} =
 * Claude Sonnet). Escolhido a cada chamada pelo orquestrador ({@code ModelTierSelector}).
 */
public enum ModelTier {
    /** conversa geral, identificação e intenção (menor custo e latência) */
    STANDARD,
    /** negociação, proposta, aceite e Pix: onde erro custa dinheiro ou gera problema de compliance */
    ADVANCED;

    public ModelTier max(ModelTier other) {
        return other == null || compareTo(other) >= 0 ? this : other;
    }
}
