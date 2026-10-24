package br.com.gestorcobranca.portal.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Segredos do acesso ao portal (puros, {@link SecureRandom}):
 * <ul>
 *   <li>código de acesso: 6 dígitos (o servidor guarda só o HMAC; 5 tentativas por código);</li>
 *   <li>token do link: 45 letras (≈ 256 bits), sem dígitos — não se confunde com telefone ou documento
 *       nos filtros de dados pessoais das variáveis de template; guardado só como SHA-256;</li>
 *   <li>protocolo do pedido de atendimento: 10 caracteres sem ambiguidade (sem 0/O, 1/I/L).</li>
 * </ul>
 */
public final class AccessSecrets {

    public static final int CODE_LENGTH = 6;
    public static final int LINK_TOKEN_LENGTH = 45;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] PROTOCOL = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private AccessSecrets() {
    }

    public static String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return sb.toString();
    }

    public static String newLinkToken() {
        return random(LETTERS, LINK_TOKEN_LENGTH);
    }

    public static String newProtocol() {
        return random(PROTOCOL, 10);
    }

    /** Formato aceito para o token do link (antes de qualquer consulta ao banco). */
    public static boolean isWellFormedLinkToken(String token) {
        if (token == null || token.length() != LINK_TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isWellFormedCode(String code) {
        return code != null && code.length() == CODE_LENGTH && code.chars().allMatch(Character::isDigit);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparação em tempo constante (hashes hexadecimais). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String random(char[] alphabet, int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = alphabet[RANDOM.nextInt(alphabet.length)];
        }
        return new String(out);
    }
}
