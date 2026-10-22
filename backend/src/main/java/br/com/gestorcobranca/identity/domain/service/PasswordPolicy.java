package br.com.gestorcobranca.identity.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Política de senha alinhada ao NIST SP 800-63B: comprimento mínimo como principal requisito,
 * diversidade de caracteres, bloqueio de senhas comuns e de senhas derivadas de dados do
 * próprio usuário. O histórico (não reutilizar as últimas N) é verificado no serviço, que
 * possui acesso aos hashes anteriores.
 */
public final class PasswordPolicy {

    private static final int MAX_LENGTH = 128;
    private static final Set<String> COMMON = Set.of(
            "123456789012", "senha1234567", "password1234", "qwertyuiop12", "abcdefghijkl", "aaaaaaaaaaaa",
            "cobranca1234", "cobranca@123", "senha@123456", "mudar@123456", "admin@123456", "brasil@12345",
            "123456@senha", "trocar123456", "iloveyou1234", "welcome12345", "changeme1234", "temporaria12");

    private final int minLength;

    public PasswordPolicy(int minLength) {
        this.minLength = minLength;
    }

    public void validate(String password, String email, String name) {
        if (password == null || password.length() < minLength) {
            throw new BusinessRuleException("password.too_short", "A senha deve ter ao menos %d caracteres".formatted(minLength));
        }
        if (password.length() > MAX_LENGTH) {
            throw new BusinessRuleException("password.too_long", "A senha deve ter no máximo %d caracteres".formatted(MAX_LENGTH));
        }
        int classes = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isLowerCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            classes++;
        }
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            classes++;
        }
        if (classes < 3) {
            throw new BusinessRuleException("password.weak",
                    "Use ao menos 3 tipos de caractere: maiúsculas, minúsculas, números e símbolos");
        }
        String normalized = normalize(password);
        if (COMMON.contains(normalized) || password.chars().distinct().count() < 5) {
            throw new BusinessRuleException("password.common", "Senha muito comum ou previsível");
        }
        if (email != null) {
            String localPart = normalize(email.substring(0, Math.max(email.indexOf('@'), 0)));
            if (localPart.length() >= 4 && normalized.contains(localPart)) {
                throw new BusinessRuleException("password.personal_data", "A senha não pode conter seu e-mail");
            }
        }
        if (name != null) {
            for (String part : normalize(name).split("\\s+")) {
                if (part.length() >= 4 && normalized.contains(part)) {
                    throw new BusinessRuleException("password.personal_data", "A senha não pode conter seu nome");
                }
            }
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
