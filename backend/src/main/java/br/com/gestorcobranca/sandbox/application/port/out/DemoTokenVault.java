package br.com.gestorcobranca.sandbox.application.port.out;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Guarda dos segredos dos tokens de demonstração da API pública. O segredo só é devolvido na emissão (o sistema
 * guarda apenas o HMAC); para a demonstração ele fica num arquivo do volume exclusivo do sandbox, com permissão 600 —
 * nunca no log nem no banco.
 */
public interface DemoTokenVault {

    /** Tokens cujo segredo já está guardado. */
    Set<UUID> storedTokenIds();

    /** Acrescenta (ou substitui, para o mesmo token) a entrada. */
    void store(Entry entry);

    /** @param token valor completo {@code gck_<prefixo>.<segredo>} */
    record Entry(UUID tokenId, String tenant, String creditor, String name, List<String> scopes, String token) {

        @Override
        public String toString() {
            return "Entry[" + tenant + "/" + name + ", token=***]";
        }
    }
}
