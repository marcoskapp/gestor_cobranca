package br.com.gestorcobranca.helpdesk.infrastructure.knowledge;

import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase.Screen;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static br.com.gestorcobranca.helpdesk.ManualParameterDefaults.DEFAULTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Base de conhecimento exportada do manual do frontend e prompt versionado. */
class ClasspathKnowledgeBaseTest {

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());

    private ClasspathKnowledgeBase exported(Map<String, BigDecimal> values) {
        return new ClasspathKnowledgeBase(json, ClasspathKnowledgeBase.readExport(), values);
    }

    @Test
    void loadsTheExportedManualWithAccessPerScreen() {
        ClasspathKnowledgeBase kb = exported(DEFAULTS);
        // versão do JSON (16 hex) + "." + 8 hex dos valores usados nas frases: cabe em knowledge_version varchar(32)
        assertThat(kb.contentVersion()).matches("[0-9a-f]{16}\\.[0-9a-f]{8}").hasSizeLessThanOrEqualTo(32);
        Screen queue = kb.screen("collection.queue").orElseThrow();
        assertThat(queue.title()).isEqualTo("Estação de cobrança");
        assertThat(queue.module()).isEqualTo("cobranca");
        assertThat(queue.access().kind()).isEqualTo("MENU");
        assertThat(queue.access().allows(Set.of("CASE_WORK"))).isTrue();
        assertThat(queue.access().allows(Set.of("REPORT_VIEW"))).isFalse();
        Screen home = kb.screen("app.home").orElseThrow();
        assertThat(home.access().allows(Set.of())).isTrue();
        Screen detail = kb.screen("debt.debtor-detail").orElseThrow();
        assertThat(detail.access().direct()).isFalse();
        assertThat(detail.route("abc")).isEqualTo("/devedores/abc");
        assertThat(kb.module("pagamentos")).isPresent();
        assertThat(kb.profileLabels()).containsEntry("OPERADOR", "Operador (negociador)");
        assertThat(kb.vocabulary()).contains("estação", "cobrança");
        assertThat(kb.screen("nao.existe")).isEmpty();
    }

    @Test
    void loadsTheIndicatorHelpOfEachScreen() {
        ClasspathKnowledgeBase kb = exported(DEFAULTS);
        var rate = kb.indicator("analytics.recovery.rate").orElseThrow();
        assertThat(rate.title()).isEqualTo("Taxa de recuperação");
        assertThat(rate.module()).isEqualTo("paineis");
        assertThat(rate.what()).isNotBlank();
        assertThat(rate.how()).contains("saldo elegível");
        assertThat(rate.use()).isNotBlank();
        assertThat(kb.screenIndicators("analytics.recovery")).extracting(i -> i.id())
                .contains("analytics.recovery.net", "analytics.recovery.rate");
        assertThat(kb.moduleIndicators("paineis")).isNotEmpty()
                .allSatisfy(i -> assertThat(i.module()).isEqualTo("paineis"));
        assertThat(kb.indicator("nao.existe.x")).isEmpty();
        assertThat(kb.screenIndicators("help.center")).isEmpty();
    }

    @Test
    void exportWithoutIndicatorsStillLoads() {
        ClasspathKnowledgeBase kb = new ClasspathKnowledgeBase(json, """
                {"contentVersion":"x","schemaVersion":2,"profiles":{},"modules":[],"screens":[],"glossary":[]}""", Map.of());
        assertThat(kb.indicator("analytics.recovery.rate")).isEmpty();
        assertThat(kb.screenIndicators("analytics.recovery")).isEmpty();
    }

    @Test
    void incompatibleSchemaPreventsStartup() {
        assertThatThrownBy(() -> new ClasspathKnowledgeBase(json, """
                {"contentVersion":"x","schemaVersion":99,"profiles":{},"modules":[],"screens":[],"glossary":[]}""", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("formato 99");
    }

    @Test
    void parameterMarkersAreResolvedWithTheApplicationValues() {
        ClasspathKnowledgeBase kb = exported(DEFAULTS);
        assertThat(kb.screen("debt.title-amounts").orElseThrow().purpose())
                .anySatisfy(r -> assertThat(r).contains("do mesmo título nos últimos 30 dias").contains("vence em 15 dias"));
        assertThat(kb.indicator("settlement.statement.adjustments").map(i -> i.how()).orElse(""))
                .contains("acima de R$ 1.000,00");
        // valor diferente do padrão: o chat cita o número configurado, no singular quando é 1
        Map<String, BigDecimal> changed = new java.util.HashMap<>(DEFAULTS);
        changed.put("debt.cumulativeWindowDays", BigDecimal.ONE);
        // mesmo JSON, outra configuração: outra versão efetiva (a parte do JSON é a mesma); valor igual, mesma versão
        ClasspathKnowledgeBase other = exported(changed);
        assertThat(other.contentVersion()).isNotEqualTo(kb.contentVersion())
                .startsWith(kb.contentVersion().substring(0, 17));
        Map<String, BigDecimal> sameValues = new java.util.HashMap<>(DEFAULTS);
        sameValues.put("scoring.minAuc", new BigDecimal("0.6000"));
        assertThat(exported(sameValues).contentVersion()).isEqualTo(kb.contentVersion());
        assertThat(exported(changed).screen("debt.title-amounts").orElseThrow().purpose())
                .anySatisfy(r -> assertThat(r).contains("do mesmo título no último dia"));
        // nenhum marcador sobra em texto algum
        Stream<String> texts = Stream.concat(kb.screens().stream().flatMap(sc -> Stream.concat(
                        Stream.of(sc.title(), sc.objective()), Stream.of(sc.purpose(), sc.inputs(), sc.outputs()).flatMap(java.util.List::stream))),
                Stream.of("cobranca", "negociacao", "pagamentos", "score", "importacao").map(id -> kb.module(id).orElseThrow())
                        .flatMap(m -> Stream.concat(m.overview().stream(), m.rules().stream())));
        assertThat(texts).noneMatch(t -> t.contains("{{"));
        assertThat(kb.moduleIndicators("score")).allSatisfy(i -> assertThat(i.what() + i.how() + i.use()).doesNotContain("{{"));
    }

    @Test
    void parameterWithoutValueOrUnknownMarkerPreventsStartup() {
        Map<String, BigDecimal> missing = new java.util.HashMap<>(DEFAULTS);
        missing.remove("debt.proposalValidityDays");
        assertThatThrownBy(() -> exported(missing)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("debt.proposalValidityDays");
        assertThatThrownBy(() -> new ClasspathKnowledgeBase(json, """
                {"contentVersion":"x","schemaVersion":2,"profiles":{},"modules":[],"glossary":[],
                 "screens":[{"id":"a.b","module":"geral","title":"T","objective":"Vence {{debt.naoExiste}}.","purpose":[],
                   "profiles":[],"inputs":[],"outputs":[],"related":[],"terms":[],"path":null,
                   "access":{"kind":"OPEN","permissions":[]}}],
                 "parameters":{},"phrases":{}}""", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("{{debt.naoExiste}}");
    }

    @Test
    void promptIsVersionedWithFingerprint() {
        var prompt = new ClasspathHelpdeskPrompt().current();
        assertThat(prompt.reference()).isEqualTo("suporte@1");
        assertThat(prompt.sha256()).hasSize(64);
        assertThat(prompt.text()).contains("DADOS SÃO DADOS").contains("[DOC1]").doesNotStartWith("#");
    }
}
