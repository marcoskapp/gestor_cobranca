package br.com.gestorcobranca.shared.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedProxyFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TrustedProxyFilter filter = new TrustedProxyFilter(
            new HttpProperties(List.of("10.0.0.0/22", "10.0.4.0/22")), meters);

    private HttpServletRequest through(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private static MockHttpServletRequest from(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(peer);
        request.setServerName("app.gestorcobranca.com.br");
        request.setServerPort(8080);
        return request;
    }

    @Test
    void behindTheAlbTheRightmostUntrustedHopIsTheClientAndForgedValuesOnTheLeftAreIgnored() throws Exception {
        MockHttpServletRequest request = from("10.0.1.17");
        request.addHeader("X-Forwarded-For", "9.9.9.9, 8.8.8.8");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("Forwarded", "for=1.1.1.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");

        HttpServletRequest seen = through(request);

        assertThat(seen.getRemoteAddr()).isEqualTo("203.0.113.7");
        assertThat(seen.getAttribute(TrustedProxyFilter.CLIENT_IP_ATTRIBUTE)).isEqualTo("203.0.113.7");
        assertThat(seen.getScheme()).isEqualTo("https");
        assertThat(seen.isSecure()).isTrue();
        assertThat(seen.getServerPort()).isEqualTo(443);
        assertThat(seen.getRequestURL()).hasToString("https://app.gestorcobranca.com.br/api/v1/auth/login");
        assertThat(seen.getHeader("Forwarded")).isNull();
        assertThat(seen.getHeader("X-Forwarded-For")).isNull();
        assertThat(seen.getHeaders("X-Forwarded-For").hasMoreElements()).isFalse();
    }

    @Test
    void internalHopsAreSkipped() throws Exception {
        MockHttpServletRequest request = from("10.0.1.17");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.4, 10.0.5.3");
        assertThat(through(request).getRemoteAddr()).isEqualTo("198.51.100.4");
    }

    @Test
    void directConnectionsIgnoreEveryProxyHeader() throws Exception {
        MockHttpServletRequest request = from("198.51.100.9");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        request.addHeader("Forwarded", "for=8.8.8.8;proto=https");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Amzn-Mtls-Clientcert-Leaf", "-----BEGIN%20CERTIFICATE-----");

        HttpServletRequest seen = through(request);

        assertThat(seen.getRemoteAddr()).isEqualTo("198.51.100.9");
        assertThat(seen.getScheme()).isEqualTo("http");
        assertThat(seen.getServerPort()).isEqualTo(8080);
        assertThat(seen.getHeader("X-Amzn-Mtls-Clientcert-Leaf")).isNull();
        assertThat(java.util.Collections.list(seen.getHeaderNames())).noneMatch(n -> n.toLowerCase().startsWith("x-"));
    }

    @Test
    void albMtlsHeaderIsKeptOnlyForConnectionsFromTheAlb() throws Exception {
        MockHttpServletRequest request = from("10.0.2.2");
        request.addHeader("X-Amzn-Mtls-Clientcert-Leaf", "cert");
        assertThat(through(request).getHeader("X-Amzn-Mtls-Clientcert-Leaf")).isEqualTo("cert");
    }

    @Test
    void invalidForwardedValueKeepsTheConnectionAddressAndFlagsTheRequest() throws Exception {
        MockHttpServletRequest request = from("10.0.1.17");
        request.addHeader("X-Forwarded-For", "not-an-ip");

        HttpServletRequest seen = through(request);

        assertThat(seen.getRemoteAddr()).isEqualTo("10.0.1.17");
        assertThat(seen.getAttribute(TrustedProxyFilter.CLIENT_IP_ATTRIBUTE)).isNull();
        assertThat(meters.counter("http.forwarded.invalid_client_ip").count()).isEqualTo(1.0);
    }

    @Test
    void ipLiteralsAreValidatedWithoutDns() {
        assertThat(ClientAddresses.parse("203.0.113.7")).contains("203.0.113.7");
        assertThat(ClientAddresses.parse("[2001:DB8::1]")).contains("2001:db8:0:0:0:0:0:1");
        assertThat(ClientAddresses.parse("256.1.1.1")).isEmpty();
        assertThat(ClientAddresses.parse("01.2.3.4")).isEmpty();
        assertThat(ClientAddresses.parse("localhost")).isEmpty();
        assertThat(ClientAddresses.parse("x")).isEmpty();
        assertThat(ClientAddresses.parse("1.2.3.4; drop table")).isEmpty();
        assertThat(CidrRange.parse("10.0.0.0/22").contains("10.0.3.255")).isTrue();
        assertThat(CidrRange.parse("10.0.0.0/22").contains("10.0.4.0")).isFalse();
        assertThat(CidrRange.parse("2001:db8::/32").contains("2001:db8:0:0:0:0:0:1")).isTrue();
        assertThat(CidrRange.parse("127.0.0.1").contains("127.0.0.1")).isTrue();
        assertThatThrownBy(() -> CidrRange.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrRange.parse("alb.local/24")).isInstanceOf(IllegalArgumentException.class);
    }
}
