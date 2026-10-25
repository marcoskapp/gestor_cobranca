package br.com.gestorcobranca.payment.domain.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PixEndpointRulesTest {

    @Test
    void internalAddressesAreRecognised() throws Exception {
        for (String internal : List.of("169.254.169.254", "10.1.2.3", "172.31.255.1", "192.168.0.10", "127.0.0.1",
                "0.0.0.0", "100.100.1.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1",
                "fd00:ec2::254", "fc00::1", "::ffff:169.254.169.254", "::ffff:10.0.0.1")) {
            assertThat(PixEndpointRules.isInternal(InetAddress.getByName(internal))).as(internal).isTrue();
        }
        for (String external : List.of("200.155.10.1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2804:14c::1",
                "::ffff:8.8.8.8")) {
            assertThat(PixEndpointRules.isInternal(InetAddress.getByName(external))).as(external).isFalse();
        }
    }

    @Test
    void allowListAcceptsExactHostsAndSubdomainWildcards() {
        List<String> allowed = List.of("api.itau.com.br", "*.bb.com.br");
        assertThat(PixEndpointRules.hostAllowed("api.itau.com.br", allowed)).isTrue();
        assertThat(PixEndpointRules.hostAllowed("API.ITAU.COM.BR", allowed)).isTrue();
        assertThat(PixEndpointRules.hostAllowed("api-pix.bb.com.br", allowed)).isTrue();
        assertThat(PixEndpointRules.hostAllowed("bb.com.br", allowed)).isFalse();
        assertThat(PixEndpointRules.hostAllowed("evilbb.com.br", allowed)).isFalse();
        assertThat(PixEndpointRules.hostAllowed("api.itau.com.br.evil.com", allowed)).isFalse();
        assertThat(PixEndpointRules.hostAllowed("qualquer.com", List.of())).isTrue();
    }
}
