package br.com.gestorcobranca.publicapi.domain.model;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Token de integração no formato {@code gck_<prefixo>.<segredo>}:
 * <ul>
 *   <li><b>prefixo</b>: 12 caracteres base32 minúsculos (60 bits), público e único na plataforma — identifica o token
 *       nas telas, nos logs e na busca (nunca é segredo);</li>
 *   <li><b>segredo</b>: 32 bytes aleatórios ({@link SecureRandom}, 256 bits) em Base64 URL sem preenchimento (43
 *       caracteres), exibido uma única vez e nunca armazenado.</li>
 * </ul>
 * O prefixo fixo {@code gck_} facilita a detecção automática de vazamentos (varredura de segredos em repositórios).
 */
public record TokenSecret(String prefix, String secret) {

    public static final String MARKER = "gck_";
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final Pattern FORMAT = Pattern.compile("gck_([a-z2-7]{12})\\.([A-Za-z0-9_-]{43})");
    private static final Pattern PREFIX = Pattern.compile("[a-z2-7]{12}");

    public TokenSecret {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(secret, "secret");
    }

    public static TokenSecret generate(SecureRandom random) {
        StringBuilder prefix = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            prefix.append(BASE32.charAt(random.nextInt(BASE32.length())));
        }
        return new TokenSecret(prefix.toString(), newSecret(random));
    }

    /** Novo segredo para o mesmo prefixo (rotação). */
    public TokenSecret rotate(SecureRandom random) {
        return new TokenSecret(prefix, newSecret(random));
    }

    private static String newSecret(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Valor completo, entregue ao cliente uma única vez. */
    public String value() {
        return MARKER + prefix + "." + secret;
    }

    /** Prefixo exibível ({@code gck_xxxxxxxxxxxx}). */
    public String displayPrefix() {
        return MARKER + prefix;
    }

    /** Interpreta o valor recebido no cabeçalho; vazio se o formato não confere (sem revelar o motivo ao cliente). */
    public static Optional<TokenSecret> parse(String value) {
        if (value == null || value.length() > 100) {
            return Optional.empty();
        }
        var m = FORMAT.matcher(value.strip());
        return m.matches() ? Optional.of(new TokenSecret(m.group(1), m.group(2))) : Optional.empty();
    }

    public static boolean isValidPrefix(String prefix) {
        return prefix != null && PREFIX.matcher(prefix).matches();
    }

    /** Nunca expõe o segredo em logs ou mensagens. */
    @Override
    public String toString() {
        return "TokenSecret[" + displayPrefix() + ".***]";
    }
}
