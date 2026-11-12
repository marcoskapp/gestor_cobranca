package br.com.gestorcobranca.portal.domain.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Conferência do nome declarado por quem negocia pela empresa (cobrança B2B, B4), pura: confere quando o primeiro e o
 * último nome declarados batem com o primeiro e o último do cadastro, sem acento e sem diferenciar maiúsculas. Nomes do
 * meio são livres ("Maria Silva" confere com "Maria Aparecida da Silva"); pontuação e hífen separam palavras.
 */
public final class DeclaredName {

    private DeclaredName() {
    }

    /** @return verdadeiro se o primeiro e o último nome conferem; nome vazio ou nulo nunca confere */
    public static boolean matches(String registered, String declared) {
        List<String> expected = words(registered);
        List<String> given = words(declared);
        if (expected.isEmpty() || given.isEmpty()) {
            return false;
        }
        return expected.getFirst().equals(given.getFirst()) && expected.getLast().equals(given.getLast());
    }

    private static List<String> words(String name) {
        if (name == null) {
            return List.of();
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return plain.isEmpty() ? List.of() : Arrays.asList(plain.split(" "));
    }
}
