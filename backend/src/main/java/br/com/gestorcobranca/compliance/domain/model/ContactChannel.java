package br.com.gestorcobranca.compliance.domain.model;

import java.util.Optional;

/** Canal de contato com o devedor. */
public enum ContactChannel {
    WHATSAPP,
    EMAIL,
    SMS,
    PHONE,
    PORTAL;

    /** Tipo do valor de contato usado pelo canal; o portal não usa telefone nem e-mail. */
    public Optional<ContactValueType> contactValueType() {
        return switch (this) {
            case WHATSAPP, SMS, PHONE -> Optional.of(ContactValueType.PHONE);
            case EMAIL -> Optional.of(ContactValueType.EMAIL);
            case PORTAL -> Optional.empty();
        };
    }
}
