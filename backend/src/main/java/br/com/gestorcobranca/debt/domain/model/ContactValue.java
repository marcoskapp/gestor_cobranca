package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Valor de contato validado e normalizado:
 * <ul>
 *   <li>telefone: E.164 brasileiro {@code +55DDDNÚMERO} — celular com 9 dígitos iniciado por 9
 *       ou fixo com 8 dígitos iniciado por 2 a 5, DDD existente;</li>
 *   <li>e-mail: minúsculo, sem espaços;</li>
 *   <li>endereço: texto livre com espaços normalizados.</li>
 * </ul>
 * {@link #hashKey()} é a forma canônica usada no blind index, que impede contatos duplicados
 * para o mesmo devedor (inclusive em reimportações com formatação diferente).
 */
public record ContactValue(ContactType type, String value) {

    public static final String PHONE_HASH_PURPOSE = "contact.phone";
    public static final String EMAIL_HASH_PURPOSE = "contact.email";
    public static final String ADDRESS_HASH_PURPOSE = "contact.address";

    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");
    private static final int EMAIL_MAX = 254;
    private static final int ADDRESS_MIN = 5;
    private static final int ADDRESS_MAX = 500;
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /** DDDs atribuídos pela Anatel. */
    private static final Set<Integer> AREA_CODES = Set.of(
            11, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 24, 27, 28, 31, 32, 33, 34, 35, 37, 38,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 51, 53, 54, 55, 61, 62, 63, 64, 65, 66, 67, 68, 69,
            71, 73, 74, 75, 77, 79, 81, 82, 83, 84, 85, 86, 87, 88, 89, 91, 92, 93, 94, 95, 96, 97, 98, 99);

    public ContactValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    /** Valida e normaliza um valor informado em texto livre. */
    public static ContactValue parse(ContactType type, String raw) {
        Guard.require(type != null, "contact.type_required", "Tipo de contato obrigatório");
        Guard.require(raw != null && !raw.isBlank(), "contact.value_required", "Valor do contato obrigatório");
        return switch (type) {
            case PHONE -> new ContactValue(type, normalizePhone(raw));
            case EMAIL -> new ContactValue(type, normalizeEmail(raw));
            case ADDRESS -> new ContactValue(type, normalizeAddress(raw));
        };
    }

    static String normalizePhone(String raw) {
        String trimmed = raw.trim();
        String digits = trimmed.replaceAll("\\D", "");
        if (trimmed.startsWith("+")) {
            if (!digits.startsWith("55")) {
                throw new BusinessRuleException("contact.phone_country_unsupported", "Somente telefones do Brasil (+55)");
            }
            digits = digits.substring(2);
        } else {
            digits = digits.replaceFirst("^0+", "");
            if ((digits.length() == 12 || digits.length() == 13) && digits.startsWith("55")) {
                digits = digits.substring(2);
            }
        }
        if (digits.length() != 10 && digits.length() != 11) {
            throw invalidPhone();
        }
        int areaCode = Integer.parseInt(digits.substring(0, 2));
        char first = digits.charAt(2);
        boolean valid = AREA_CODES.contains(areaCode)
                && (digits.length() == 11 ? first == '9' : first >= '2' && first <= '5');
        if (!valid) {
            throw invalidPhone();
        }
        return "+55" + digits;
    }

    static String normalizeEmail(String raw) {
        String email = raw.trim().toLowerCase(Locale.ROOT);
        Guard.require(email.length() <= EMAIL_MAX && EMAIL.matcher(email).matches(), "contact.email_invalid",
                "E-mail inválido");
        return email;
    }

    static String normalizeAddress(String raw) {
        String address = raw.trim().replaceAll("\\s+", " ");
        Guard.require(address.length() >= ADDRESS_MIN && address.length() <= ADDRESS_MAX, "contact.address_invalid",
                "Endereço deve ter entre %d e %d caracteres".formatted(ADDRESS_MIN, ADDRESS_MAX));
        return address;
    }

    private static BusinessRuleException invalidPhone() {
        return new BusinessRuleException("contact.phone_invalid", "Telefone inválido: informe DDD e número");
    }

    public boolean isMobilePhone() {
        return type == ContactType.PHONE && value.length() == 14;
    }

    /** Finalidade do blind index conforme o tipo. */
    public String hashPurpose() {
        return switch (type) {
            case PHONE -> PHONE_HASH_PURPOSE;
            case EMAIL -> EMAIL_HASH_PURPOSE;
            case ADDRESS -> ADDRESS_HASH_PURPOSE;
        };
    }

    /** Forma canônica para o blind index (endereço sem acentos, pontuação e caixa). */
    public String hashKey() {
        if (type != ContactType.ADDRESS) {
            return value;
        }
        String folded = DIACRITICS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[.,;:\\-/#º°ª]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Valor mascarado para exibição e auditoria. */
    public String masked() {
        return switch (type) {
            case PHONE -> "(" + value.substring(3, 5) + ") " + "*****-" + value.substring(value.length() - 4);
            case EMAIL -> value.charAt(0) + "***@" + value.substring(value.indexOf('@') + 1);
            case ADDRESS -> value.split(" ", 2)[0] + " ***";
        };
    }

    @Override
    public String toString() {
        // nunca expor o valor completo em toString (evita vazamento acidental em logs)
        return type + "(" + masked() + ")";
    }
}
