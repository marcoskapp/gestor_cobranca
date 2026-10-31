package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions.Decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Regras das decisões sobre os títulos (excluir do lote, reincluir e confirmar a leitura visual), individuais ou em
 * massa. A ação aplica o permitido e devolve, agrupado por motivo, o que ficou de fora.
 * <p>
 * Decisão 667 (aprovada): título lido visualmente com <strong>confiança baixa</strong> só é confirmado
 * <strong>um a um</strong> — uma decisão individual (um título, escolhido pelo número). Na ação em massa ele fica de
 * fora da confirmação; excluí-lo em massa é permitido.
 */
public final class RecordDecisionPolicy {

    /** Motivos de títulos que ficaram de fora (códigos estáveis, exibidos na tela). */
    public static final String ALREADY_EXCLUDED = "already_excluded";
    public static final String NOT_EXCLUDED = "not_excluded";
    public static final String NOT_VISUAL = "not_visual";
    public static final String ALREADY_CONFIRMED = "already_confirmed";
    public static final String LOW_CONFIDENCE_ONE_BY_ONE = "low_confidence_one_by_one";

    private static final Map<String, String> MESSAGES = Map.of(
            ALREADY_EXCLUDED, "já estavam excluídos do lote",
            NOT_EXCLUDED, "não estavam excluídos",
            NOT_VISUAL, "não foram lidos visualmente (só a leitura visual pede confirmação)",
            ALREADY_CONFIRMED, "já estavam confirmados",
            LOW_CONFIDENCE_ONE_BY_ONE, "têm confiança baixa: confirme um a um, conferindo com a imagem da página");

    /**
     * Título alvo da decisão.
     *
     * @param visual        lido visualmente (PDF sem camada de texto)
     * @param lowConfidence leitura visual abaixo da confiança mínima
     */
    public record Target(int ordinal, boolean visual, boolean lowConfidence) {
    }

    public record Skipped(String code, String message, List<Integer> ordinals) {
    }

    /** @param decisions decisões resultantes (iguais às anteriores quando nada foi aplicado) */
    public record Outcome(RecordDecisions decisions, List<Integer> applied, List<Skipped> skipped) {

        public int skippedCount() {
            return skipped.stream().mapToInt(s -> s.ordinals().size()).sum();
        }
    }

    /**
     * @param individual decisão sobre um único título escolhido pelo número (não em massa)
     */
    public Outcome decide(RecordDecisions current, Decision decision, List<Target> targets, boolean individual) {
        List<Integer> applied = new ArrayList<>();
        Map<String, List<Integer>> skipped = new LinkedHashMap<>();
        for (Target t : targets) {
            String reason = switch (decision) {
                case EXCLUDE -> current.isExcluded(t.ordinal()) ? ALREADY_EXCLUDED : null;
                case INCLUDE -> current.isExcluded(t.ordinal()) ? null : NOT_EXCLUDED;
                case CONFIRM -> !t.visual() ? NOT_VISUAL
                        : current.isConfirmed(t.ordinal()) ? ALREADY_CONFIRMED
                        : t.lowConfidence() && !individual ? LOW_CONFIDENCE_ONE_BY_ONE
                        : null;
            };
            if (reason == null) {
                applied.add(t.ordinal());
            } else {
                skipped.computeIfAbsent(reason, k -> new ArrayList<>()).add(t.ordinal());
            }
        }
        RecordDecisions next = applied.isEmpty() ? current : current.apply(decision, new TreeSet<>(applied));
        List<Skipped> groups = new ArrayList<>();
        skipped.forEach((code, ordinals) -> groups.add(new Skipped(code, MESSAGES.get(code), List.copyOf(ordinals))));
        return new Outcome(next, List.copyOf(applied), List.copyOf(groups));
    }
}
