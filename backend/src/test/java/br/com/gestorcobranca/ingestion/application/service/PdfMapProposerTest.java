package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.ingestion.application.port.out.PdfModelPort;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextPage;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextWord;
import br.com.gestorcobranca.ingestion.support.pdf.PdfFixtures;
import br.com.gestorcobranca.shared.ai.LanguageModel.Cost;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelRequest;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelResponse;
import br.com.gestorcobranca.shared.ai.LanguageModel.Role;
import br.com.gestorcobranca.shared.ai.LanguageModel.StopReason;
import br.com.gestorcobranca.shared.ai.LanguageModel.ToolCall;
import br.com.gestorcobranca.shared.ai.LanguageModel.Usage;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proposta do mapa pela IA (modelo dublê): texto mascarado, validação no servidor e rodada de correção. */
class PdfMapProposerTest {

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final Deque<String> answers = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    private final PdfModelPort model = new PdfModelPort() {
        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            requests.add(request);
            return new ModelResponse(null, List.of(new ToolCall("c" + requests.size(), PdfMapProposer.MAP_TOOL, answers.poll())),
                    StopReason.TOOL_USE, new Usage(1000, 200), new Cost(new BigDecimal("0.003"), "USD"), "fake",
                    Duration.ofMillis(10));
        }
    };

    private PdfMapProposer proposer() {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("model", model);
        return new PdfMapProposer(factory.getBeanProvider(PdfModelPort.class), json, PdfImportProperties.defaults());
    }

    private static PdfText text() {
        List<TextWord> words = List.of(new TextWord("CPF/CNPJ:", 10, 50, 10, 16), new TextWord("123.456.789-09", 60, 120, 10, 16),
                new TextWord("ana@exemplo.test", 130, 200, 10, 16));
        return new PdfText(List.of(new TextPage(1, 595, 842, List.of(new TextLine(1, 1, words)))));
    }

    @Test
    void validProposalOnFirstCallWithMaskedText() {
        answers.add(json.toJson(PdfFixtures.historyMap()));
        PdfMapProposer.Proposal proposal = proposer().propose(UUID.randomUUID(), UUID.randomUUID(), text());
        assertThat(proposal.map()).isEqualTo(PdfFixtures.historyMap());
        assertThat(proposal.problems()).isEmpty();
        assertThat(proposal.usage()).hasSize(1).first().satisfies(u -> {
            assertThat(u.inputTokens()).isEqualTo(1000);
            assertThat(u.cost()).isEqualByComparingTo("0.003");
        });
        String sent = requests.getFirst().messages().getFirst().content();
        assertThat(sent).contains("CPF/CNPJ:").contains("999.999.999-99").contains("email@mascarado")
                .doesNotContain("123.456").doesNotContain("ana@");
        assertThat(requests.getFirst().metadata()).doesNotContainValue("ana@exemplo.test");
        assertThat(requests.getFirst().tools().getFirst().parametersSchema()).contains("headerLines");
    }

    @Test
    void invalidProposalIsSentBackOnceForCorrection() {
        ExtractionMap good = PdfFixtures.simpleMap();
        ExtractionMap bad = new ExtractionMap(1, "x", good.numberFormat(), good.debtor(), List.of(), good.table(),
                List.of(new ExtractionMap.ControlTotal("Total:", null, "nome")), List.of());
        answers.add(json.toJson(bad));
        answers.add(json.toJson(good));
        PdfMapProposer.Proposal proposal = proposer().propose(UUID.randomUUID(), UUID.randomUUID(), text());
        assertThat(proposal.map()).isEqualTo(good);
        assertThat(proposal.usage()).hasSize(2);
        ModelRequest second = requests.get(1);
        assertThat(second.messages()).extracting(m -> m.role()).containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL);
        assertThat(second.messages().get(2).content()).contains("pdf_map.control_target");
    }

    @Test
    void stillInvalidAfterCorrectionReturnsTheProblems() {
        answers.add("{\"schemaVersion\": 1}");
        answers.add("não é json");
        PdfMapProposer.Proposal proposal = proposer().propose(UUID.randomUUID(), UUID.randomUUID(), text());
        assertThat(proposal.map()).isNull();
        assertThat(proposal.problems()).isNotEmpty();
        assertThat(proposal.usage()).hasSize(2);
    }
}
