package br.com.gestorcobranca.shared.storage;

import java.time.LocalDate;
import java.util.UUID;

/** Montagem e validação das chaves de objeto com isolamento por tenant. */
final class StorageKeys {

    private StorageKeys() {
    }

    static String build(UUID tenantId, FileCategory category, UUID fileId, String originalName, LocalDate date) {
        return "tenants/%s/%s/%04d/%02d/%s-%s".formatted(tenantId, category.prefix(), date.getYear(),
                date.getMonthValue(), fileId, sanitize(originalName));
    }

    /** Garante que a chave pertence ao tenant corrente (impede acesso cruzado por chave forjada). */
    static void requireTenant(String key, UUID tenantId) {
        if (key == null || !key.startsWith("tenants/" + tenantId + "/") || key.contains("..")) {
            throw new SecurityException("Arquivo fora do escopo do tenant");
        }
    }

    static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "arquivo";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }
}
