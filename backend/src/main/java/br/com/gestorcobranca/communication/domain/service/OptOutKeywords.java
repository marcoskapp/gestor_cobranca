package br.com.gestorcobranca.communication.domain.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Palavras de descadastro (RB07). A mensagem do devedor é opt-out quando, sem acentos, pontuação e
 * espaços extras, é exatamente uma das palavras (ou expressões) reconhecidas — "SAIR", "PARAR",
 * "STOP", "Não quero mais receber"… Frases que apenas contêm a palavra ("vou sair do emprego") não
 * contam, para não descadastrar por engano.
 */
public final class OptOutKeywords {

    private static final Set<String> KEYWORDS = Set.of("SAIR", "PARAR", "PARE", "STOP", "CANCELAR", "DESCADASTRAR",
            "DESCADASTRO", "REMOVER", "UNSUBSCRIBE", "NAO QUERO MAIS RECEBER", "NAO QUERO RECEBER",
            "PARAR DE RECEBER", "SAIR DA LISTA", "NAO ME ENVIE MAIS MENSAGENS");

    private OptOutKeywords() {
    }

    /** @return a palavra reconhecida (normalizada), se a mensagem for um pedido de descadastro */
    public static Optional<String> match(String text) {
        if (text == null || text.length() > 60) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
        return KEYWORDS.contains(normalized) ? Optional.of(normalized) : Optional.empty();
    }
}
