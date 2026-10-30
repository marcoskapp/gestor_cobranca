package br.com.gestorcobranca.publicapi.infrastructure.network;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CidrClientAddressRulesTest {

    private final CidrClientAddressRules rules = new CidrClientAddressRules();

    @Test
    void normalizesAndMatchesCidrRanges() {
        List<String> list = rules.normalize(List.of("200.10.0.0/16", " 189.1.2.3 ", "2001:db8::/48", ""));
        assertThat(list).containsExactly("200.10.0.0/16", "189.1.2.3/32", "2001:db8::/48");
        assertThat(rules.allows(list, "200.10.99.1")).isTrue();
        assertThat(rules.allows(list, "189.1.2.3")).isTrue();
        assertThat(rules.allows(list, "189.1.2.4")).isFalse();
        assertThat(rules.allows(list, "2001:db8:0:1::5")).isTrue();
        assertThat(rules.allows(list, null)).isFalse();
        assertThat(rules.allows(List.of(), null)).isTrue();
    }

    @Test
    void rejectsInvalidOrTooWideRanges() {
        assertThatThrownBy(() -> rules.normalize(List.of("meu-servidor.com"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> rules.normalize(List.of("0.0.0.0/0"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> rules.normalize(List.of("::/0"))).isInstanceOf(BusinessRuleException.class);
    }
}
