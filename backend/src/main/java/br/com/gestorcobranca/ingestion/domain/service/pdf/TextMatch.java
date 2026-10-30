package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextWord;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Comparação de rótulos com as palavras de uma linha: sem diferenciar maiúsculas e acentos, mas com a pontuação
 * (o rótulo {@code Cliente:} não casa com a palavra {@code Cliente} de um título). Rótulos de várias palavras casam com
 * palavras consecutivas.
 */
final class TextMatch {

    private TextMatch() {
    }

    static String normalize(String text) {
        String stripped = Normalizer.normalize(text.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static List<String> tokens(String label) {
        if (label == null || label.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : normalize(label).split(" ")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Primeira posição, a partir de {@code from}, em que as palavras da linha casam com o rótulo; -1 se não casar. */
    static int find(TextLine line, String label, int from) {
        List<String> tokens = tokens(label);
        if (tokens.isEmpty()) {
            return -1;
        }
        List<TextWord> words = line.words();
        for (int i = Math.max(0, from); i + tokens.size() <= words.size(); i++) {
            boolean match = true;
            for (int k = 0; k < tokens.size(); k++) {
                if (!normalize(words.get(i + k).text()).equals(tokens.get(k))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    static boolean contains(TextLine line, String label) {
        return find(line, label, 0) >= 0;
    }

    /** O texto aparece na linha como trecho (comparação normalizada; para as linhas a ignorar). */
    static boolean containsText(TextLine line, String text) {
        return text != null && !text.isBlank() && normalize(line.text()).contains(normalize(text));
    }

    static int size(String label) {
        return tokens(label).size();
    }
}
