package br.com.gestorcobranca.shared.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validação de endereços IP literais vindos da rede ou de cabeçalhos. Nunca consulta DNS: um valor
 * que não seja IPv4 ou IPv6 literal é recusado (e não gravado em colunas {@code inet}).
 */
public final class ClientAddresses {

    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientAddresses() {
    }

    /**
     * @return o endereço normalizado (IPv6 na forma canônica do JDK) ou vazio se o valor não for um
     *         IP literal válido
     */
    public static Optional<String> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        if (IPV4.matcher(v).matches()) {
            return Optional.of(v);
        }
        // com ':' o JDK trata o texto como IPv6 literal e não faz resolução de nome
        if (v.indexOf(':') >= 0 && IPV6_CHARS.matcher(v).matches()) {
            try {
                return Optional.of(InetAddress.getByName(v).getHostAddress());
            } catch (UnknownHostException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public static boolean isValid(String value) {
        return parse(value).isPresent();
    }

    /** Valor seguro para gravar em coluna {@code inet}: o próprio IP normalizado ou nulo. */
    public static String orNull(String value) {
        return parse(value).orElse(null);
    }

    /** Bytes do endereço literal já validado. */
    static byte[] bytes(String literal) {
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Endereço inválido: " + literal, e);
        }
    }
}
