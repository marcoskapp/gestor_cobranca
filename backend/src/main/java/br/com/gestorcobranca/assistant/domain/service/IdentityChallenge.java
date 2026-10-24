package br.com.gestorcobranca.assistant.domain.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê, do texto do devedor, a resposta à confirmação de identidade — primeiros dígitos do CPF (3 a 6;
 * ou o CPF completo, do qual só os 3 primeiros são usados) e a data de nascimento ({@code dd/mm/aaaa},
 * com {@code /}, {@code -} ou {@code .}). A verificação é feita no servidor (debt); estes valores
 * nunca são enviados ao modelo.
 */
public final class IdentityChallenge {

    private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b");
    private static final Pattern FULL_CPF = Pattern.compile("\\b(\\d{3})\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
    private static final Pattern PREFIX = Pattern.compile("(?<![\\d/.-])\\b(\\d{3,6})\\b(?![\\d/.-])");

    public record Answer(String documentPrefix, LocalDate birthDate) {
    }

    private IdentityChallenge() {
    }

    public static Optional<Answer> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher d = DATE.matcher(text);
        if (!d.find()) {
            return Optional.empty();
        }
        LocalDate birth;
        try {
            birth = LocalDate.of(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1)));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        String rest = text.substring(0, d.start()) + " " + text.substring(d.end());
        Matcher full = FULL_CPF.matcher(rest);
        if (full.find()) {
            return Optional.of(new Answer(full.group(1), birth));
        }
        Matcher p = PREFIX.matcher(rest);
        return p.find() ? Optional.of(new Answer(p.group(1), birth)) : Optional.empty();
    }
}
