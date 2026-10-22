package br.com.gestorcobranca.shared.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Criptografia simétrica de campos sensíveis (documento, telefone, e-mail, endereço) com
 * AES-256-GCM (confidencialidade + integridade). Formato persistido:
 * {@code <keyId>:<base64(iv[12] || ciphertext || tag[16])>}.
 * <p>
 * O identificador da chave no prefixo permite rotação sem migração imediata dos dados.
 */
@Component
public class FieldEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKey> keys = new HashMap<>();
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(CryptoProperties properties) {
        properties.keys().forEach((id, base64) -> {
            byte[] raw = Base64.getDecoder().decode(base64);
            if (raw.length != 32) {
                throw new IllegalStateException("Chave de criptografia '%s' deve ter 256 bits".formatted(id));
            }
            if (id.contains(":")) {
                throw new IllegalStateException("Id de chave não pode conter ':'");
            }
            keys.put(id, new SecretKeySpec(raw, "AES"));
        });
        if (!keys.containsKey(properties.activeKeyId())) {
            throw new IllegalStateException("Chave ativa '%s' não configurada".formatted(properties.activeKeyId()));
        }
        this.activeKeyId = properties.activeKeyId();
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return activeKeyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar campo", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        int separator = stored.indexOf(':');
        if (separator <= 0) {
            throw new IllegalStateException("Valor criptografado em formato inválido");
        }
        String keyId = stored.substring(0, separator);
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Chave '%s' não disponível para descriptografia".formatted(keyId));
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(separator + 1));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar campo (chave incorreta ou dado adulterado)", e);
        }
    }

    /** Indica se o valor foi gravado com chave diferente da ativa (candidato a re-criptografia). */
    public boolean needsRotation(String stored) {
        return stored != null && !stored.startsWith(activeKeyId + ":");
    }
}
