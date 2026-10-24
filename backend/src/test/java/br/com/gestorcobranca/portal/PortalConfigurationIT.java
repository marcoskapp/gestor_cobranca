package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.ConfigurationCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.LogoUpload;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Configuração do portal por credor ({@code PORTAL_MANAGE}): marca com contraste AA, carteiras do credor,
 * endereço único global, logotipo pelo conteúdo, escopo por credor e isolamento entre assessorias (RLS).
 */
class PortalConfigurationIT extends PortalTestSupport {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @BeforeEach
    void setUp() {
        provision("pcfg");
    }

    @Test
    void brandingRulesAndPortfolioOwnership() {
        ConfigurationCommand base = configuration("banco-b-" + UUID.randomUUID().toString().substring(0, 6), "NONE");
        // credor já tem portal
        assertThatThrownBy(() -> asAdmin(() -> portalConfigurations.create(base))).isInstanceOf(ConflictException.class);
        ConfigurationCommand forB = new ConfigurationCommand(creditorB, base.slug(), true, "Loja B", "#FFD700", "#0B6E4F",
                Set.of(), Set.of("WHATSAPP"), "NONE", 24, NOTICE, null);
        // amarelo com texto branco não atinge 4,5:1
        assertThatThrownBy(() -> asAdmin(() -> portalConfigurations.create(forB))).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("contraste");
        // carteira de outro credor
        ConfigurationCommand wrongPortfolio = new ConfigurationCommand(creditorB, base.slug(), true, "Loja B", "#7A1F5C",
                "#0B6E4F", Set.of(portfolioA), Set.of("WHATSAPP"), "NONE", 24, NOTICE, null);
        assertThatThrownBy(() -> asAdmin(() -> portalConfigurations.create(wrongPortfolio)))
                .hasMessageContaining("credor do portal");
        // endereço já usado (global, entre assessorias)
        ConfigurationCommand sameSlug = new ConfigurationCommand(creditorB, slug, true, "Loja B", "#7A1F5C", "#0B6E4F",
                Set.of(), Set.of("WHATSAPP"), "NONE", 24, NOTICE, null);
        assertThatThrownBy(() -> asAdmin(() -> portalConfigurations.create(sameSlug))).isInstanceOf(ConflictException.class);
        var created = asAdmin(() -> portalConfigurations.create(new ConfigurationCommand(creditorB, base.slug(), false,
                "Loja B", "#7A1F5C", "#0B6E4F", Set.of(), Set.of("WHATSAPP", "EMAIL"), "CONTRACT_SUFFIX", 24, NOTICE, null)));
        assertThat(created.otpChannels()).containsExactly("WHATSAPP", "EMAIL");
        assertThat(created.publicUrl()).endsWith("/portal/" + base.slug());
        assertThat(count("select count(*) from shared.audit_event where action = 'portal.configuration_created'")).isEqualTo(2);
    }

    @Test
    void logoIsCheckedByContentAndServedPublicly() throws Exception {
        assertThatThrownBy(() -> asAdmin(() -> portalConfigurations.uploadLogo(portal.id(), new LogoUpload("logo.png",
                "image/png", 10, new ByteArrayInputStream("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8))))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("PNG");
        var withLogo = asAdmin(() -> portalConfigurations.uploadLogo(portal.id(), new LogoUpload("logo.svg", "image/svg+xml",
                PNG.length, new ByteArrayInputStream(PNG))));
        assertThat(withLogo.hasLogo()).isTrue();
        HttpResponse<byte[]> logo = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/portal/v1/sites/" + slug + "/logo")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(logo.statusCode()).isEqualTo(200);
        assertThat(logo.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(logo.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(logo.body()).isEqualTo(PNG);
        assertThat(get("/api/portal/v1/sites/" + slug, null).json().get("hasLogo").asBoolean()).isTrue();
    }

    @Test
    void creditorScopeAndTenantIsolation() {
        // gestor restrito ao credor B não vê nem altera o portal do credor A
        var restricted = new Actor(Actor.ActorType.USER, manager, tenant, Ids.newId(), "gestor-b",
                DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.GESTOR)).findFirst().orElseThrow()
                        .permissions(), CreditorScope.of(Set.of(creditorB)));
        assertThat(tenantExecution.callAs(restricted, () -> portalConfigurations.list())).isEmpty();
        assertThatThrownBy(() -> tenantExecution.callAs(restricted, () -> portalConfigurations.update(portal.id(),
                configuration(slug, "NONE")))).isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);
        // operador não gerencia o portal
        assertThatThrownBy(() -> asOperator(() -> portalConfigurations.list())).isInstanceOf(ScopeAccessDeniedException.class);

        login(CPF_MARIA);
        // outra assessoria: RLS esconde configurações, sessões, desafios e bloqueios
        String otherSlug = "outra-" + UUID.randomUUID().toString().substring(0, 8);
        UUID other = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(otherSlug, "Outra Ltda", "Outra", "11.444.777/0001-61", "Admin",
                        "admin@" + otherSlug + ".test"))).tenant().id();
        for (String table : new String[]{"portal_configuration", "portal_session", "access_challenge", "access_throttle"}) {
            Long visible = tenantExecution.callInTenant(other, "test", () -> jdbcTemplate.queryForObject(
                    "select count(*) from portal." + table, Long.class));
            assertThat(visible).as(table).isZero();
            assertThat(count("select count(*) from portal." + table)).as(table).isPositive();
        }
        // a rota pública é global, mas só tem endereço e ids
        assertThat(tenantExecution.callInTenant(other, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from portal.portal_route where slug = ?", Long.class, slug))).isEqualTo(1);
        // sessões e configuração não são removidas pela aplicação (rastreabilidade)
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from portal.portal_session")))
                .rootCause().hasMessageContaining("permission denied");
    }
}
