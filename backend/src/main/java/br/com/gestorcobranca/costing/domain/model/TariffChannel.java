package br.com.gestorcobranca.costing.domain.model;

import java.util.Optional;

/**
 * Canal tarifado de mensagem. WhatsApp: por conversa iniciada pela empresa, por categoria do template no provedor
 * (a Meta cobra uma vez por janela de 24 h); SMS e e-mail: por mensagem saída. O valor é sempre por unidade.
 */
@org.springframework.modulith.NamedInterface("usecases")
public enum TariffChannel {
    WHATSAPP_UTILITY,
    WHATSAPP_MARKETING,
    WHATSAPP_AUTHENTICATION,
    SMS,
    EMAIL;

    /** Tarifa do WhatsApp para a categoria do template no provedor ({@code UTILITY}, {@code MARKETING}, ...). */
    public static Optional<TariffChannel> forWhatsAppCategory(String providerCategory) {
        if (providerCategory == null) {
            return Optional.empty();
        }
        return switch (providerCategory) {
            case "UTILITY" -> Optional.of(WHATSAPP_UTILITY);
            case "MARKETING" -> Optional.of(WHATSAPP_MARKETING);
            case "AUTHENTICATION" -> Optional.of(WHATSAPP_AUTHENTICATION);
            default -> Optional.empty();
        };
    }
}
