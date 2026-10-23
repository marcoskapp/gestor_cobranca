package br.com.gestorcobranca.negotiation.domain.model.index;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Índice de correção monetária mantido manualmente pela assessoria (ex.: IPCA, INPC, IGP-M),
 * com taxas mensais em {@link MonetaryIndexRate}. Uma integração com fonte oficial (BCB/IBGE)
 * pode substituir a tabela manual pela porta {@code MonetaryIndexProvider}, sem mudar o cálculo.
 */
@Entity
@Table(name = "monetary_index", schema = "negotiation")
public class MonetaryIndex extends TenantScopedEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "source", length = 200)
    private String source;

    protected MonetaryIndex() {
    }

    public MonetaryIndex(String code, String name, String source) {
        Guard.require(code != null && code.matches("[A-Z0-9_]{2,20}"), "index.code_invalid",
                "Código do índice: 2 a 20 caracteres em maiúsculas, dígitos ou _");
        this.code = code;
        describe(name, source);
    }

    public void describe(String name, String source) {
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 120, "index.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(source == null || source.length() <= 200, "index.source_too_long", "Fonte até 200 caracteres");
        this.name = name.trim();
        this.source = source;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }
}
