package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DeadlineSource;
import br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentAction;
import br.com.gestorcobranca.collection.domain.model.DisputeDeadlineRule;
import br.com.gestorcobranca.collection.domain.model.DisputeOutcome;
import br.com.gestorcobranca.collection.domain.model.DisputeTemplateKind;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras puras da rotina de contestações: prazos por tipo/credor, distribuição, modelos e ajuste sugerido. */
class DisputeRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    static final UUID CREDITOR = UUID.randomUUID();
    static final UUID OTHER = UUID.randomUUID();

    @Test
    void deadlineHierarchyGoesFromCreditorAndTypeToTenantDefault() {
        List<DisputeDeadlineRule> rules = List.of(
                new DisputeDeadlineRule(null, DisputeType.FRAUD, 15, 10),
                new DisputeDeadlineRule(CREDITOR, null, 7, 5),
                new DisputeDeadlineRule(CREDITOR, DisputeType.FRAUD, 20, 12));

        var creditorAndType = DisputeDeadlines.resolve(TODAY, CREDITOR, DisputeType.FRAUD, null, rules, 10, 7);
        assertThat(creditorAndType.source()).isEqualTo(DeadlineSource.CREDITOR_TYPE);
        assertThat(creditorAndType.responseDue()).isEqualTo(TODAY.plusDays(20));
        assertThat(creditorAndType.internalDue()).isEqualTo(TODAY.plusDays(12));

        var creditorOnly = DisputeDeadlines.resolve(TODAY, CREDITOR, DisputeType.WRONG_AMOUNT, null, rules, 10, 7);
        assertThat(creditorOnly.source()).isEqualTo(DeadlineSource.CREDITOR);
        assertThat(creditorOnly.responseDays()).isEqualTo(7);

        var typeOnly = DisputeDeadlines.resolve(TODAY, OTHER, DisputeType.FRAUD, null, rules, 10, 7);
        assertThat(typeOnly.source()).isEqualTo(DeadlineSource.TYPE);
        assertThat(typeOnly.responseDays()).isEqualTo(15);

        var fallback = DisputeDeadlines.resolve(TODAY, OTHER, DisputeType.OTHER, null, rules, 10, 7);
        assertThat(fallback.source()).isEqualTo(DeadlineSource.TENANT_DEFAULT);
        assertThat(fallback.responseDue()).isEqualTo(TODAY.plusDays(10));
        assertThat(fallback.internalDue()).isEqualTo(TODAY.plusDays(7));
    }

    @Test
    void internalDeadlineNeverPassesTheResponseDeadline() {
        // padrão interno maior que a resposta: fica limitado à resposta
        var capped = DisputeDeadlines.resolve(TODAY, OTHER, DisputeType.OTHER, null, List.of(), 5, 9);
        assertThat(capped.internalDue()).isEqualTo(capped.responseDue());
        // prazo informado na abertura: o interno segue o padrão, limitado ao informado
        var informed = DisputeDeadlines.resolve(TODAY, OTHER, DisputeType.OTHER, TODAY.plusDays(3), List.of(), 10, 7);
        assertThat(informed.source()).isEqualTo(DeadlineSource.INFORMED);
        assertThat(informed.responseDue()).isEqualTo(TODAY.plusDays(3));
        assertThat(informed.internalDue()).isEqualTo(TODAY.plusDays(3));
        assertThatThrownBy(() -> new DisputeDeadlineRule(null, null, 10, 5)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new DisputeDeadlineRule(CREDITOR, null, 5, 6)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void distributionPicksTheLeastLoadedThenWhoWaitedLongest() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        assertThat(DisputeDistributor.pick(Set.of(a, b, c), Map.of(a, 3L, b, 1L, c, 1L),
                Map.of(b, Instant.parse("2026-11-04T10:00:00Z"), c, Instant.parse("2026-11-03T10:00:00Z"))))
                .contains(c);
        // quem nunca recebeu vem antes no empate
        assertThat(DisputeDistributor.pick(Set.of(b, c), Map.of(), Map.of(b, Instant.parse("2026-11-01T10:00:00Z"))))
                .contains(c);
        assertThat(DisputeDistributor.pick(Set.of(), Map.of(), Map.of())).isEmpty();
    }

    @Test
    void creditorTemplatesAskForTheDocumentEachAllegationRequires() {
        assertThat(body(DisputeType.ALREADY_PAID)).contains("comprovante da baixa");
        assertThat(body(DisputeType.FRAUD)).contains("contrato assinado").contains("documentos apresentados");
        assertThat(body(DisputeType.WRONG_AMOUNT)).contains("memória de cálculo");
        assertThat(body(DisputeType.NOT_RECOGNIZED)).contains("contrato").contains("origem da dívida");
        assertThat(body(DisputeType.PRODUCT_OR_SERVICE)).contains("comprovante de entrega");
        // minimização: o pedido ao credor não tem variável de nome, documento ou contato do devedor
        for (DisputeType type : DisputeType.values()) {
            String text = body(type);
            assertThat(text).doesNotContain("{{primeiro_nome}}").contains("{{titulos}}").contains("{{link_evidencias}}");
            DisputeTemplates.validate(DisputeTemplateKind.CREDITOR_REQUEST, type.name(), "x", text);
        }
        for (DisputeOutcome outcome : DisputeOutcome.values()) {
            var text = DisputeTemplates.defaultFor(DisputeTemplateKind.DEBTOR_RESPONSE, outcome.name());
            assertThat(text.body()).contains("{{fundamentacao}}");
            DisputeTemplates.validate(DisputeTemplateKind.DEBTOR_RESPONSE, outcome.name(), text.subject(), text.body());
        }
    }

    @Test
    void templateValidationAndFilling() {
        assertThatThrownBy(() -> DisputeTemplates.validate(DisputeTemplateKind.CREDITOR_REQUEST, "FRAUD", "Assunto",
                "Olá {{primeiro_nome}}")).isInstanceOf(BusinessRuleException.class).hasMessageContaining("primeiro_nome");
        assertThatThrownBy(() -> DisputeTemplates.validate(DisputeTemplateKind.DEBTOR_RESPONSE, "FRAUD", "a", "b"))
                .isInstanceOf(BusinessRuleException.class);
        String filled = DisputeTemplates.fill("Protocolo {{ protocolo }} de {{credor}} ({{desconhecida}})",
                Map.of("protocolo", "AB12CD34", "credor", "Banco $1"));
        assertThat(filled).isEqualTo("Protocolo AB12CD34 de Banco $1 ()");
        assertThat(DisputeTemplates.hasPlaceholders(filled)).isFalse();
        assertThat(DisputeTemplates.hasPlaceholders("ver {{link_evidencias}}")).isTrue();
    }

    @Test
    void upheldDisputesSuggestTheTitleActionByType() {
        assertThat(DisputeAdjustmentAction.suggestedFor(DisputeType.ALREADY_PAID, DisputeOutcome.UPHELD))
                .isEqualTo(DisputeAdjustmentAction.SETTLE_BY_PAYMENT);
        assertThat(DisputeAdjustmentAction.suggestedFor(DisputeType.WRONG_AMOUNT, DisputeOutcome.PARTIALLY_UPHELD))
                .isEqualTo(DisputeAdjustmentAction.ADJUST_AMOUNT);
        assertThat(DisputeAdjustmentAction.suggestedFor(DisputeType.FRAUD, DisputeOutcome.UPHELD))
                .isEqualTo(DisputeAdjustmentAction.CANCEL_TITLES);
        assertThat(DisputeAdjustmentAction.suggestedFor(DisputeType.FRAUD, DisputeOutcome.PARTIALLY_UPHELD))
                .isEqualTo(DisputeAdjustmentAction.ADJUST_AMOUNT);
        assertThat(DisputeAdjustmentAction.suggestedFor(DisputeType.PRESCRIBED, DisputeOutcome.REJECTED)).isNull();
    }

    private static String body(DisputeType type) {
        return DisputeTemplates.defaultFor(DisputeTemplateKind.CREDITOR_REQUEST, type.name()).body();
    }
}
