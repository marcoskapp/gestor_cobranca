package br.com.gestorcobranca.analytics.domain.model;

import java.util.UUID;

/**
 * Sugestão de revisão da política para um recorte (decisão 1087): só aponta o sinal e a evidência; nunca altera a
 * política — quem gerencia a política decide, cria um rascunho e publica.
 *
 * @param kind HIGH_EARLY_BREAK, DISCOUNT_AT_LIMIT, DISCOUNT_UNUSED, PORTAL_WITHOUT_OFFER ou CANNOT_AFFORD
 */
public record PolicySuggestion(Kind kind, UUID portfolioId, String delayBucket) {

    public enum Kind {
        /** Quebra precoce alta: entrada mínima maior ou menos parcelas. */
        HIGH_EARLY_BREAK,
        /** Desconto no limite com boa adimplência: avaliar ampliar o desconto da faixa (teste controlado). */
        DISCOUNT_AT_LIMIT,
        /** Desconto máximo pouco usado: avaliar reduzir a escada de concessão. */
        DISCOUNT_UNUSED,
        /** Muitas consultas do portal sem oferta: avaliar o teto da alçada do portal ou as condições da faixa. */
        PORTAL_WITHOUT_OFFER,
        /** Muitos "nenhuma condição cabe": avaliar parcela mínima ou mais parcelas. */
        CANNOT_AFFORD
    }
}
