package br.com.gestorcobranca.ingestion.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;

/** Coluna de um layout: campo canônico e onde encontrá-lo no arquivo (cabeçalho ou posição). */
@Embeddable
public class LayoutColumn {

    static final int HEADER_MAX = 120;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 40)
    private CanonicalField field;

    @Column(name = "header", length = HEADER_MAX)
    private String header;

    @Column(name = "position")
    private Integer position;

    protected LayoutColumn() {
    }

    LayoutColumn(CanonicalField field, String header, Integer position) {
        this.field = field;
        this.header = header;
        this.position = position;
    }

    public CanonicalField getField() {
        return field;
    }

    public String getHeader() {
        return header;
    }

    public Integer getPosition() {
        return position;
    }

    FileLayout.Column toColumn() {
        return new FileLayout.Column(field, header, position);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LayoutColumn other && field == other.field;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(field);
    }
}
