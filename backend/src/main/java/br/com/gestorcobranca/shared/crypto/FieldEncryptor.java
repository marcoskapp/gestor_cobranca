package br.com.gestorcobranca.shared.crypto;

import br.com.gestorcobranca.shared.security.ActorAuthentication;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * AES-256-GCM (confidencialidade + integridade). Formatos persistidos:
 * <ul>
 *   <li>{@code <keyId>v2:<base64(iv[12] || ciphertext || tag[16])>} (atual): o AAD vincula o valor à
 *       chave <strong>e à assessoria</strong> ({@code v2|<keyId>|<tenantId>}). Um valor copiado para um
 *       registro de outra assessoria (backup restaurado, acesso direto ao banco) não decifra;</li>
 *   <li>{@code <keyId>:<...>} (legado, AAD só com a chave): continua legível; passa para v2 quando o
 *       registro é gravado de novo. Leituras contadas em {@code crypto.legacy_format_reads}.</li>
 * </ul>
 * A assessoria é a do ator corrente, a mesma que define {@code app.tenant_id} da transação e, pelo RLS,
 * as únicas linhas visíveis e graváveis — portanto sempre a do registro. Tabela, coluna e id do
 * registro não entram no AAD: um {@code AttributeConverter} do JPA não os recebe, e valores copiados
 * entre colunas da mesma assessoria por SQL legítimo continuam válidos (decisão registrada em
 * docs/tecnico/seguranca.md). O identificador da chave no prefixo permite rotação sem migração imediata.
 */
@Component
public class FieldEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String V2 = "v2";
    /** AAD de valores gravados fora de um ator (não ocorre em tabelas de assessoria, protegidas por RLS). */
    private static final String NO_TENANT = "-";

    private final Map<String, SecretKey> keys = new HashMap<>();
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();
    private final Counter legacyReads;

    public FieldEncryptor(CryptoProperties properties) {
        this(properties, null);
    }

    @Autowired
    public FieldEncryptor(CryptoProperties properties, MeterRegistry meterRegistry) {
        this.legacyReads = meterRegistry == null ? null : meterRegistry.counter("crypto.legacy_format_reads");
        properties.keys().forEach((id, base64) -> {
            byte[] raw = Base64.getDecoder().decode(base64);
            if (raw.length != 32) {
                throw new IllegalStateException("Chave de criptografia '%s' deve ter 256 bits".formatted(id));
            }
            if (!id.matches("[a-z][a-z0-9]{0,15}") || id.matches(".*v\\d+")) {
                throw new IllegalStateException(
                        "Id de chave '%s' inválido: letras minúsculas e dígitos, sem terminar em v<número>".formatted(id));
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
            cipher.updateAAD(aadV2(activeKeyId, currentTenant()));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return activeKeyId + V2 + ":" + Base64.getEncoder().encodeToString(payload);
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
        String prefix = stored.substring(0, separator);
        boolean v2 = !keys.containsKey(prefix) && prefix.endsWith(V2);
        String keyId = v2 ? prefix.substring(0, prefix.length() - V2.length()) : prefix;
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Chave '%s' não disponível para descriptografia".formatted(keyId));
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(separator + 1));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH));
            if (v2) {
                cipher.updateAAD(aadV2(keyId, currentTenant()));
            } else {
                cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
                if (legacyReads != null) {
                    legacyReads.increment();
                }
            }
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar campo (chave incorreta ou dado adulterado)", e);
        }
    }

    /** Indica se o valor foi gravado com chave diferente da ativa ou no formato legado (candidato a re-criptografia). */
    public boolean needsRotation(String stored) {
        return stored != null && !stored.startsWith(activeKeyId + V2 + ":");
    }

    private static byte[] aadV2(String keyId, String tenant) {
        return (V2 + "|" + keyId + "|" + tenant).getBytes(StandardCharsets.UTF_8);
    }

    /** Assessoria do ator corrente (a mesma de {@code app.tenant_id} na transação e, pelo RLS, a do registro). */
    private static String currentTenant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthentication actorAuthentication && actorAuthentication.actor().tenantId() != null) {
            return actorAuthentication.actor().tenantId().toString();
        }
        return NO_TENANT;
    }
}
