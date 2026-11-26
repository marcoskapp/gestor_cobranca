package br.com.gestorcobranca.sandbox;

import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.ActivationStart;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.CreditorLoginResult;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.LoginStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O perfil {@code sandbox} não afrouxa nada do portal do credor (RS-85): nenhuma chave
 * {@code app.security.creditor-portal.*} em {@code application-sandbox.yml} (sessão, limites por IP e convites ficam os
 * de {@code application.yml}); o "sem MFA" do sandbox (decisão 633) é só a lista de perfis internos. E os records da
 * autenticação do credor não expõem segredo nem token no {@code toString} (RS-82).
 */
class SandboxCreditorPortalNotRelaxedTest {

    @Test
    void sandboxProfileHasNoCreditorPortalSecurityKey() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-sandbox.yml"));
        Properties sandbox = yaml.getObject();
        assertThat(sandbox).isNotNull();
        assertThat(sandbox.stringPropertyNames()).isNotEmpty()
                .noneMatch(k -> k.startsWith("app.security.creditor-portal"))
                .noneMatch(k -> k.startsWith("app.security.cors"))
                // o MFA desligado do sandbox é só a lista de perfis internos obrigatórios
                .filteredOn(k -> k.startsWith("app.security.mfa")).containsExactly("app.security.mfa.required-for-roles");
    }

    @Test
    void creditorAuthenticationRecordsHideSecretsAndTokens() {
        ActivationStart start = new ActivationStart("Pessoa", "p***@x.test", "Banco", "Assessoria",
                "JBSWY3DPEHPK3PXP", "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP");
        assertThat(start.toString()).doesNotContain("JBSWY3DPEHPK3PXP").doesNotContain("otpauth").doesNotContain("p***");
        CreditorLoginResult result = new CreditorLoginResult(LoginStatus.AUTHENTICATED, "eyJ-acesso", Instant.EPOCH,
                "refresh-opaco", Instant.EPOCH, "eyJ-etapa", null);
        assertThat(result.toString()).doesNotContain("eyJ").doesNotContain("refresh-opaco").contains("AUTHENTICATED");
    }
}
