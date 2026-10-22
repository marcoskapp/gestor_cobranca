package br.com.gestorcobranca.shared.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

/** Mapeia {@link Percentage} para coluna {@code numeric(9,4)}. Aplicado automaticamente. */
@Converter(autoApply = true)
public class PercentageConverter implements AttributeConverter<Percentage, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Percentage attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public Percentage convertToEntityAttribute(BigDecimal dbData) {
        return dbData == null ? null : Percentage.of(dbData);
    }
}
