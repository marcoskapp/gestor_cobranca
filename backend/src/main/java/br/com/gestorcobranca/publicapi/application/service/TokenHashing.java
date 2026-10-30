package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.PublicApiProperties;
import br.com.gestorcobranca.publicapi.domain.service.TokenHasher;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chave do servidor para o HMAC dos segredos ({@code app.public-api.token-hash-keys}, segredo
 * {@code app/public-api-token-hash-key}) e o gerador de números aleatórios dos tokens. A subida falha sem chave ou com
 * chave menor que 256 bits.
 */
@Component
class TokenHashing {

    private final TokenHasher hasher;
    private final SecureRandom random = new SecureRandom();

    TokenHashing(PublicApiProperties properties) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        properties.tokenHashKeys().forEach((id, value) -> {
            if (value != null && !value.isBlank()) {
                keys.put(id, Base64.getDecoder().decode(value.strip()));
            }
        });
        this.hasher = new TokenHasher(keys, properties.activeTokenHashKeyId());
    }

    TokenHasher hasher() {
        return hasher;
    }

    SecureRandom random() {
        return random;
    }
}
