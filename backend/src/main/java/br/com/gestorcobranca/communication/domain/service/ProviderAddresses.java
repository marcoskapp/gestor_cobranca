package br.com.gestorcobranca.communication.domain.service;

import java.util.Locale;
import java.util.Optional;

/**
 * Conversão entre o endereço informado pelo provedor e o contato normalizado do cadastro
 * (telefone E.164 brasileiro {@code +55DDDNÚMERO}, e-mail minúsculo).
 */
public final class ProviderAddresses {

    private ProviderAddresses() {
    }

    /**
     * {@code wa_id} do WhatsApp (dígitos com país, sem "+") → E.164. Números brasileiros de celular
     * podem chegar sem o nono dígito (formato antigo: 55 + DDD + 8 dígitos começando por 6–9),
     * que é reinserido. Fora do Brasil ou inválido → vazio (mensagem ignorada).
     */
    public static Optional<String> fromWhatsApp(String waId) {
        if (waId == null) {
            return Optional.empty();
        }
        String digits = waId.replaceAll("\\D", "");
        if (!digits.startsWith("55")) {
            return Optional.empty();
        }
        String national = digits.substring(2);
        if (national.length() == 10 && national.charAt(2) >= '6') {
            national = national.substring(0, 2) + "9" + national.substring(2);
        }
        if (national.length() != 11 && national.length() != 10) {
            return Optional.empty();
        }
        if (national.charAt(0) == '0' || national.charAt(1) == '0') {
            return Optional.empty();
        }
        return Optional.of("+55" + national);
    }

    /** E.164 → destinatário da Cloud API (dígitos, sem "+"). */
    public static String toWhatsApp(String e164) {
        return e164.replaceAll("\\D", "");
    }

    public static Optional<String> email(String address) {
        if (address == null || address.isBlank() || !address.contains("@")) {
            return Optional.empty();
        }
        return Optional.of(address.trim().toLowerCase(Locale.ROOT));
    }
}
