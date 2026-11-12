package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static br.com.gestorcobranca.shared.security.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Menu por tarefa (decisão D6): áreas de consulta separadas de ver o dado em outra tela. */
class DefaultRolesTest {

    private final Map<String, Set<Permission>> roles = DefaultRoles.forTenant().stream()
            .collect(Collectors.toMap(DefaultRoles.Definition::code, DefaultRoles.Definition::permissions,
                    (a, b) -> a, java.util.LinkedHashMap::new));

    @Test
    void operadorVeOScoreDoCasoMasNaoAbreAAreaDoScore() {
        Set<Permission> operador = roles.get(DefaultRoles.OPERADOR);
        assertThat(operador).contains(SCORING_VIEW, POLICY_VIEW, CREDITOR_VIEW, PORTFOLIO_VIEW)
                .doesNotContain(SCORING_AREA_VIEW, POLICY_AREA_VIEW, ORGANIZATION_AREA_VIEW);
    }

    @Test
    void supervisorConsultaPoliticasMasNaoAAreaDoScoreNemOsCadastros() {
        assertThat(roles.get(DefaultRoles.SUPERVISOR)).contains(POLICY_AREA_VIEW)
                .doesNotContain(SCORING_AREA_VIEW, ORGANIZATION_AREA_VIEW);
    }

    @Test
    void quemTinhaAAreaComoTarefaContinuaAbrindo() {
        for (String code : Set.of(DefaultRoles.GESTOR, DefaultRoles.ESTRATEGIA, DefaultRoles.AUDITOR)) {
            assertThat(roles.get(code)).as(code).contains(SCORING_AREA_VIEW);
        }
        for (String code : Set.of(DefaultRoles.GESTOR, DefaultRoles.FINANCEIRO, DefaultRoles.AUDITOR,
                DefaultRoles.JURIDICO, DefaultRoles.ANALISTA_CARTEIRA, DefaultRoles.ESTRATEGIA,
                DefaultRoles.COMPLIANCE, DefaultRoles.INTEGRACAO)) {
            assertThat(roles.get(code)).as(code).contains(ORGANIZATION_AREA_VIEW);
        }
        assertThat(roles.get(DefaultRoles.ADMIN)).contains(SCORING_AREA_VIEW, POLICY_AREA_VIEW, ORGANIZATION_AREA_VIEW);
    }

    @Test
    void permissaoDeAreaSoComAPermissaoDeConsultaDosDados() {
        Map<Permission, Function<Set<Permission>, Boolean>> data = Map.of(
                SCORING_AREA_VIEW, p -> p.contains(SCORING_VIEW),
                POLICY_AREA_VIEW, p -> p.contains(POLICY_VIEW),
                ORGANIZATION_AREA_VIEW, p -> p.contains(CREDITOR_VIEW) && p.contains(PORTFOLIO_VIEW));
        roles.forEach((code, permissions) -> data.forEach((area, hasData) -> {
            if (permissions.contains(area)) {
                assertThat(hasData.apply(permissions)).as(code + " " + area).isTrue();
            }
        }));
    }

    @Test
    void categoriaDoMarcadorDeVulnerabilidadeSoParaComplianceGestorEAdministrador() {
        for (String code : Set.of(DefaultRoles.COMPLIANCE, DefaultRoles.GESTOR, DefaultRoles.ADMIN)) {
            assertThat(roles.get(code)).as(code).contains(VULNERABILITY_SENSITIVE_VIEW);
        }
        assertThat(roles.get(DefaultRoles.SUPERVISOR)).contains(VULNERABILITY_MANAGE)
                .doesNotContain(VULNERABILITY_SENSITIVE_VIEW);
        assertThat(roles.get(DefaultRoles.OPERADOR)).contains(VULNERABILITY_REGISTER, COMPLAINT_REGISTER)
                .doesNotContain(VULNERABILITY_MANAGE, VULNERABILITY_SENSITIVE_VIEW, COMPLAINT_MANAGE);
        assertThat(roles.get(DefaultRoles.AUDITOR)).doesNotContain(VULNERABILITY_SENSITIVE_VIEW);
    }

    @Test
    void margemPorCarteiraSoParaGestaoFinanceiroEAuditoriaEOsCadastrosDeCustoSoComFinanceiroEAdministrador() {
        for (String code : Set.of(DefaultRoles.GESTOR, DefaultRoles.FINANCEIRO, DefaultRoles.ADMIN, DefaultRoles.AUDITOR)) {
            assertThat(roles.get(code)).as(code).contains(COST_VIEW);
        }
        for (String code : Set.of(DefaultRoles.FINANCEIRO, DefaultRoles.ADMIN)) {
            assertThat(roles.get(code)).as(code).contains(COST_MANAGE, COST_RATE_MANAGE);
        }
        roles.forEach((code, permissions) -> {
            if (!Set.of(DefaultRoles.FINANCEIRO, DefaultRoles.ADMIN).contains(code)) {
                assertThat(permissions).as(code).doesNotContain(COST_MANAGE, COST_RATE_MANAGE);
            }
            if (!Set.of(DefaultRoles.GESTOR, DefaultRoles.FINANCEIRO, DefaultRoles.ADMIN, DefaultRoles.AUDITOR)
                    .contains(code)) {
                assertThat(permissions).as(code).doesNotContain(COST_VIEW);
            }
        });
    }
}
