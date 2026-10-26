package br.com.gestorcobranca.assistant.domain.model;

/**
 * Como a política escolhe o nível de modelo ({@link br.com.gestorcobranca.shared.ai.ModelTier}) de cada chamada.
 * <ul>
 *   <li>{@code BY_STAGE} (padrão): {@code ADVANCED} nas etapas de negociação, proposta, aceite e Pix;
 *       {@code STANDARD} no resto (saudação, identificação, saldo, dúvidas);</li>
 *   <li>{@code ADVANCED_ONLY}: {@code ADVANCED} em todas as chamadas — padrão de mercado para piloto, enquanto
 *       a qualidade do nível padrão não foi medida nas conversas de referência.</li>
 * </ul>
 * Políticas gravadas antes da opção (campo ausente no jsonb) valem como {@code BY_STAGE}.
 */
public enum ModelRouting {
    BY_STAGE,
    ADVANCED_ONLY
}
