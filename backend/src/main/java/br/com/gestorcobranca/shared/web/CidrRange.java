package br.com.gestorcobranca.shared.web;

import java.util.Arrays;
import java.util.Optional;

/** Faixa de endereços em notação CIDR ({@code 10.0.0.0/20}, {@code 2001:db8::/32}); um IP sem prefixo é /32 ou /128. */
public record CidrRange(byte[] network, int prefix) {

    public CidrRange {
        network = network.clone();
    }

    public static CidrRange parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Faixa CIDR vazia");
        }
        String v = value.trim();
        int slash = v.indexOf('/');
        String address = slash < 0 ? v : v.substring(0, slash);
        Optional<String> literal = ClientAddresses.parse(address);
        if (literal.isEmpty()) {
            throw new IllegalArgumentException("Faixa CIDR inválida: " + value);
        }
        byte[] bytes = ClientAddresses.bytes(literal.get());
        int max = bytes.length * 8;
        int prefix;
        try {
            prefix = slash < 0 ? max : Integer.parseInt(v.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Faixa CIDR inválida: " + value, e);
        }
        if (prefix < 0 || prefix > max) {
            throw new IllegalArgumentException("Prefixo CIDR inválido: " + value);
        }
        return new CidrRange(bytes, prefix);
    }

    /** @param literal IP literal já validado ({@link ClientAddresses#parse}) */
    public boolean contains(String literal) {
        byte[] candidate = ClientAddresses.bytes(literal);
        if (candidate.length != network.length) {
            return false;
        }
        int full = prefix / 8;
        int rest = prefix % 8;
        if (!Arrays.equals(Arrays.copyOf(candidate, full), Arrays.copyOf(network, full))) {
            return false;
        }
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (candidate[full] & mask) == (network[full] & mask);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CidrRange other && prefix == other.prefix && Arrays.equals(network, other.network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefix;
    }

    @Override
    public String toString() {
        return Arrays.toString(network) + "/" + prefix;
    }
}
