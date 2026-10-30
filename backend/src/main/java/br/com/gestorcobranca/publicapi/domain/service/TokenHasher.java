package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.publicapi.domain.model.TokenSecret;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Hash do segredo do token: HMAC-SHA256 com uma chave do servidor ("pepper", fora do banco), versionada por id para
 * permitir rotação da chave.
 * <p>
 * Por que HMAC e não Argon2/bcrypt: o segredo é aleatório com 256 bits de entropia, então ataque de dicionário ou
 * força bruta é inviável mesmo com hash rápido; funções lentas existem para senhas escolhidas por pessoas. Um hash
 * lento por requisição tornaria a API vulnerável a negação de serviço (cada tentativa custaria dezenas de MB e
 * milissegundos de CPU). A chave do servidor garante que um vazamento só do banco não permita sequer conferir um
 * token candidato. A comparação é em tempo constante.
 */
public final class TokenHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;

    /** @param keys id da chave → bytes (ao menos 32) */
    public TokenHasher(Map<String, byte[]> keys, String activeKeyId) {
        Objects.requireNonNull(activeKeyId, "activeKeyId");
        if (keys == null || !keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("Chave ativa do hash de tokens ausente: " + activeKeyId);
        }
        keys.forEach((id, raw) -> {
            if (raw == null || raw.length < 32) {
                throw new IllegalStateException("Chave do hash de tokens '%s' deve ter ao menos 256 bits".formatted(id));
            }
        });
        this.keys = keys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                e -> new SecretKeySpec(e.getValue(), ALGORITHM)));
        this.activeKeyId = activeKeyId;
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /** Hash com a chave ativa (criação e rotação). */
    public String hash(TokenSecret token) {
        return hash(activeKeyId, token);
    }

    public String hash(String keyId, TokenSecret token) {
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Chave do hash de tokens desconhecida: " + keyId);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(("v1|" + token.prefix() + "|" + token.secret()).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular o hash do token", e);
        }
    }

    /** Confere em tempo constante; chave desconhecida (removida da configuração) = não confere. */
    public boolean matches(String keyId, String expectedHash, TokenSecret presented) {
        if (keyId == null || expectedHash == null || !keys.containsKey(keyId)) {
            return false;
        }
        byte[] actual = hash(keyId, presented).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
