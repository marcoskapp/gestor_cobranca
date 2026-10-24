package br.com.gestorcobranca.assistant.domain.service;

import java.text.Normalizer;
import java.util.Locale;

/** Forma canônica para regras determinísticas: minúsculas, sem acentos, espaços simples. */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String noMarks = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
