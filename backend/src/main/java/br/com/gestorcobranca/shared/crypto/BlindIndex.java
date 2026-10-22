package br.com.gestorcobranca.shared.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Índice cego (HMAC-SHA256) para buscar por igualdade em campos criptografados — por exemplo,
 * localizar um devedor pelo CPF sem armazenar o CPF em claro. O índice inclui o tenant, de modo
 * que o mesmo documento gera índices diferentes em assessorias diferentes.
 */
@Component
public class BlindIndex {

    private final SecretKeySpec key;

    public BlindIndex(CryptoProperties properties) {
        byte[] raw = Base64.getDecoder().decode(properties.blindIndexKey());
        if (raw.length < 32) {
            throw new IllegalStateException("Chave de blind index deve ter ao menos 256 bits");
        }
        this.key = new SecretKeySpec(raw, "HmacSHA256");
    }

    /**
     * @param tenantId   tenant dono do dado
     * @param purpose    finalidade (ex.: {@code debtor.document}, {@code contact.phone})
     * @param normalized valor já normalizado (somente dígitos, minúsculas…)
     */
    public String of(UUID tenantId, String purpose, String normalized) {
        if (normalized == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update((tenantId + "|" + purpose + "|").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular blind index", e);
        }
    }
}
