package br.com.gestorcobranca.collection.domain.model;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Canal de uma interação com o devedor. */
public enum InteractionChannel {
    PHONE,
    WHATSAPP,
    EMAIL,
    SMS,
    IN_PERSON,
    PORTAL,
    LETTER;

    /** Canais avaliados pelo porteiro de contato na elegibilidade (contato ativo à distância). */
    public static Set<InteractionChannel> gateChannels() {
        return EnumSet.of(PHONE, WHATSAPP, EMAIL, SMS);
    }

    /** Tipo de contato do devedor usado pelo canal (PHONE ou EMAIL), quando houver. */
    public Optional<String> contactType() {
        return switch (this) {
            case PHONE, WHATSAPP, SMS -> Optional.of("PHONE");
            case EMAIL -> Optional.of("EMAIL");
            default -> Optional.empty();
        };
    }
}
