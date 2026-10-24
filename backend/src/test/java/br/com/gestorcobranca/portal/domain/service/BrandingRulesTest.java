package br.com.gestorcobranca.portal.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandingRulesTest {

    @Test
    void slugIsNormalizedAndValidated() {
        assertThat(BrandingRules.slug(" Banco-A ")).isEqualTo("banco-a");
        assertThatThrownBy(() -> BrandingRules.slug("ab")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BrandingRules.slug("banco_a")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BrandingRules.slug("-banco")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BrandingRules.slug("admin")).hasMessageContaining("reservado");
    }

    @Test
    void colorsMustMeetWcagContrastWithWhite() {
        assertThat(BrandingRules.primaryColor("#1d4e89")).isEqualTo("#1D4E89");
        assertThat(BrandingRules.contrastWithWhite("#000000")).isEqualTo(21.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(BrandingRules.contrastWithWhite("#FFFFFF")).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThatThrownBy(() -> BrandingRules.primaryColor("#FFD700")).hasMessageContaining("4,5:1");
        // laranja médio passa como destaque (3:1), mas não como cor de botão com texto (4,5:1)
        assertThat(BrandingRules.accentColor("#C25E00")).isEqualTo("#C25E00");
        assertThatThrownBy(() -> BrandingRules.primaryColor("#E07000")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BrandingRules.primaryColor("blue")).hasMessageContaining("#RRGGBB");
    }

    @Test
    void imageTypeComesFromContentNeverFromTheName() {
        assertThat(BrandingRules.detectImageType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .hasValue("image/png");
        assertThat(BrandingRules.detectImageType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}))
                .hasValue("image/jpeg");
        assertThat(BrandingRules.detectImageType("RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)))
                .hasValue("image/webp");
        assertThat(BrandingRules.detectImageType("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                .getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
}
