package br.com.gestorcobranca.helpdesk.infrastructure.knowledge;

import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase.Screen;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Base de conhecimento exportada do manual do frontend e prompt versionado. */
class ClasspathKnowledgeBaseTest {

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());

    @Test
    void loadsTheExportedManualWithAccessPerScreen() {
        ClasspathKnowledgeBase kb = new ClasspathKnowledgeBase(json);
        assertThat(kb.contentVersion()).hasSize(16);
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
        ClasspathKnowledgeBase kb = new ClasspathKnowledgeBase(json);
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
                {"contentVersion":"x","schemaVersion":1,"profiles":{},"modules":[],"screens":[],"glossary":[]}""");
        assertThat(kb.indicator("analytics.recovery.rate")).isEmpty();
        assertThat(kb.screenIndicators("analytics.recovery")).isEmpty();
    }

    @Test
    void incompatibleSchemaPreventsStartup() {
        assertThatThrownBy(() -> new ClasspathKnowledgeBase(json, """
                {"contentVersion":"x","schemaVersion":99,"profiles":{},"modules":[],"screens":[],"glossary":[]}"""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("formato 99");
    }

    @Test
    void promptIsVersionedWithFingerprint() {
        var prompt = new ClasspathHelpdeskPrompt().current();
        assertThat(prompt.reference()).isEqualTo("suporte@1");
        assertThat(prompt.sha256()).hasSize(64);
        assertThat(prompt.text()).contains("DADOS SÃO DADOS").contains("[DOC1]").doesNotStartWith("#");
    }
}
