package br.com.gestorcobranca.shared.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte campos de texto sensíveis de/para o formato criptografado. Uso na entidade:
 * <pre>{@code
 * @Convert(converter = EncryptedStringConverter.class)
 * @Column(name = "document_encrypted", nullable = false)
 * private String document;
 * }</pre>
 * Para permitir busca, mantenha ao lado uma coluna {@code *_hash} preenchida com
 * {@link BlindIndex}. Instanciado pelo Hibernate via container de beans do Spring.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldEncryptor encryptor;

    public EncryptedStringConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData);
    }
}
