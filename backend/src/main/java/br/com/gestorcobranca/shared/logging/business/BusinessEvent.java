package br.com.gestorcobranca.shared.logging.business;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato de negócio relevante para gestão e inteligência (ex.: acordo formalizado, pagamento
 * confirmado, contato efetivo). Diferente do log técnico, descreve <em>o que aconteceu na
 * operação</em>, com as dimensões necessárias para análise: credor, carteira, operador, canal,
 * valor e atributos específicos.
 * <p>
 * Regras: nunca incluir documento, nome, telefone ou e-mail do devedor; use ids.
 * Tipos de evento ficam em {@link BusinessEventTypes}.
 */
public final class BusinessEvent {

    private final String type;
    private UUID creditorId;
    private UUID portfolioId;
    private UUID debtorId;
    private UUID caseId;
    private String referenceType;
    private UUID referenceId;
    private String channel;
    private BigDecimal amount;
    private Long durationMs;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    private BusinessEvent(String type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public static BusinessEvent of(String type) {
        return new BusinessEvent(type);
    }

    public BusinessEvent creditor(UUID creditorId) {
        this.creditorId = creditorId;
        return this;
    }

    public BusinessEvent portfolio(UUID portfolioId) {
        this.portfolioId = portfolioId;
        return this;
    }

    public BusinessEvent debtor(UUID debtorId) {
        this.debtorId = debtorId;
        return this;
    }

    public BusinessEvent collectionCase(UUID caseId) {
        this.caseId = caseId;
        return this;
    }

    /** Agregado principal do evento (ex.: "agreement", id do acordo). */
    public BusinessEvent reference(String referenceType, UUID referenceId) {
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        return this;
    }

    public BusinessEvent channel(String channel) {
        this.channel = channel;
        return this;
    }

    public BusinessEvent amount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public BusinessEvent amount(br.com.gestorcobranca.shared.domain.Money money) {
        this.amount = money == null ? null : money.amount();
        return this;
    }

    public BusinessEvent durationMs(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    /** Atributo adicional (valores simples: texto, número, booleano, enum). */
    public BusinessEvent attr(String key, Object value) {
        if (value != null) {
            attributes.put(key, value instanceof Enum<?> e ? e.name() : value);
        }
        return this;
    }

    public String type() {
        return type;
    }

    public UUID creditorId() {
        return creditorId;
    }

    public UUID portfolioId() {
        return portfolioId;
    }

    public UUID debtorId() {
        return debtorId;
    }

    public UUID caseId() {
        return caseId;
    }

    public String referenceType() {
        return referenceType;
    }

    public UUID referenceId() {
        return referenceId;
    }

    public String channel() {
        return channel;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Long durationMs() {
        return durationMs;
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
