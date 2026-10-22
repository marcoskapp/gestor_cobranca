package br.com.gestorcobranca.shared.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro de auditoria de uma alteração relevante (M09): quem, quando, o quê, antes/depois e
 * por quê. Obrigatório para operações financeiras, mudanças de status, aprovações, alterações
 * de política, permissões e dados cadastrais de devedores.
 * <p>
 * {@code before}/{@code after} devem conter apenas os campos alterados. Dados pessoais
 * (documento, telefone, e-mail) entram mascarados; o valor completo já está protegido no
 * próprio registro de origem.
 */
public final class AuditEntry {

    private final String action;
    private final String entityType;
    private final UUID entityId;
    private UUID creditorId;
    private String reason;
    private final Map<String, Object> before = new LinkedHashMap<>();
    private final Map<String, Object> after = new LinkedHashMap<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    private AuditEntry(String action, String entityType, UUID entityId) {
        this.action = Objects.requireNonNull(action, "action");
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.entityId = Objects.requireNonNull(entityId, "entityId");
    }

    /**
     * @param action     verbo no passado, ex.: {@code agreement.formalized}, {@code title.suspended}
     * @param entityType tipo do agregado, ex.: {@code agreement}
     * @param entityId   id do agregado
     */
    public static AuditEntry of(String action, String entityType, UUID entityId) {
        return new AuditEntry(action, entityType, entityId);
    }

    public AuditEntry creditor(UUID creditorId) {
        this.creditorId = creditorId;
        return this;
    }

    public AuditEntry reason(String reason) {
        this.reason = reason;
        return this;
    }

    /** Registra a alteração de um campo (ignorada se os valores forem iguais). */
    public AuditEntry change(String field, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            before.put(field, normalize(oldValue));
            after.put(field, normalize(newValue));
        }
        return this;
    }

    /** Estado inicial (criação). */
    public AuditEntry created(String field, Object value) {
        after.put(field, normalize(value));
        return this;
    }

    public AuditEntry meta(String key, Object value) {
        if (value != null) {
            metadata.put(key, normalize(value));
        }
        return this;
    }

    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof br.com.gestorcobranca.shared.domain.Money m) {
            return m.amount().toPlainString();
        }
        if (value instanceof br.com.gestorcobranca.shared.domain.Percentage p) {
            return p.value().toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return value.toString();
    }

    public String action() {
        return action;
    }

    public String entityType() {
        return entityType;
    }

    public UUID entityId() {
        return entityId;
    }

    public UUID creditorId() {
        return creditorId;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> before() {
        return before;
    }

    public Map<String, Object> after() {
        return after;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}
