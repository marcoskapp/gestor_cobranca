package br.com.gestorcobranca.shared.crypto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Chaves de criptografia de campos sensíveis. Fornecidas por variáveis de ambiente injetadas
 * no deploy (nunca versionadas). Suporta rotação: novos dados usam {@code activeKeyId}; dados
 * antigos continuam legíveis enquanto a chave antiga estiver em {@code keys}.
 *
 * @param keys          id da chave → chave AES-256 em Base64 (32 bytes)
 * @param activeKeyId   chave usada para novas gravações
 * @param blindIndexKey chave HMAC-SHA256 em Base64 para índices de busca (não rotacionável sem reindexação)
 */
@Validated
@ConfigurationProperties(prefix = "app.crypto")
public record CryptoProperties(
        @NotEmpty Map<String, String> keys,
        @NotBlank String activeKeyId,
        @NotBlank String blindIndexKey) {
}
