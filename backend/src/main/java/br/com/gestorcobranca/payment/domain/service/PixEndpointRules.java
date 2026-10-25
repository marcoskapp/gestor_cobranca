package br.com.gestorcobranca.payment.domain.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/**
 * Regras para os endereços de PSP informados pela assessoria (URL da API Pix e do token OAuth2). Evitam
 * que a aplicação seja usada para alcançar serviços internos (SSRF) — a VPC, o próprio host ou o serviço
 * de metadados da instância — e permitem restringir os PSPs a uma lista de hosts conhecidos.
 */
public final class PixEndpointRules {

    private PixEndpointRules() {
    }

    /**
     * Endereço que não pode ser destino de chamada a PSP: qualquer-local, loopback, link-local (inclui
     * metadados 169.254.169.254 e fd00:ec2::254), privado (RFC 1918, ULA fc00::/7), CGNAT 100.64/10,
     * multicast, "este host" 0/8, benchmark 198.18/15, reservado 240/4 e IPv4 mapeado/compatível em IPv6.
     */
    public static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 || first >= 240
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 192 && second == 0 && (b[2] & 0xFF) == 0);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true; // fc00::/7 (inclui fd00:ec2::254, metadados IPv6 da AWS)
            }
            boolean mappedOrCompatible = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mappedOrCompatible = false;
                    break;
                }
            }
            if (mappedOrCompatible && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF))) {
                try {
                    return isInternal(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param patterns hosts exatos ou curingas de subdomínio ({@code *.psp.com.br}); vazio = qualquer host
     */
    public static boolean hostAllowed(String host, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String p = pattern.toLowerCase(Locale.ROOT);
            if (p.startsWith("*.") ? h.endsWith(p.substring(1)) && h.length() > p.length() - 1 : h.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** Host escrito como IP literal (v4 ou v6 entre colchetes). */
    public static boolean isIpLiteral(String host) {
        return host.startsWith("[") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
