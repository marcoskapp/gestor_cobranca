package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextPage;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextWord;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Texto posicionado <strong>minimizado e mascarado</strong> enviado ao modelo para propor o mapa: o modelo precisa da
 * estrutura (rótulos, posições, formatos), não dos valores. Todo dígito vira {@code 9} (documentos, telefones, datas e
 * valores mantêm só o formato) e todo e-mail vira {@code email@mascarado}. Amostragem: a 1ª página até
 * {@code firstPageLines} linhas; das seguintes, as primeiras e as últimas linhas, até {@code maxLines} no total.
 */
public final class PromptText {

    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final int HEAD_LINES = 12;
    private static final int TAIL_LINES = 6;

    private PromptText() {
    }

    public static String mask(String text) {
        return DIGIT.matcher(EMAIL.matcher(text).replaceAll("email@mascarado")).replaceAll("9");
    }

    /** @return texto para o modelo e a quantidade de linhas enviadas */
    public static Rendered render(PdfText text, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int sent = 0;
        int total = text.pages().stream().mapToInt(p -> p.lines().size()).sum();
        int firstPageLines = Math.max(1, maxLines * 2 / 3);
        for (TextPage page : text.pages()) {
            if (sent >= maxLines) {
                break;
            }
            sb.append("=== página ").append(page.number()).append(" (").append(Math.round(page.width())).append(" x ")
                    .append(Math.round(page.height())).append(" pt, ").append(page.lines().size()).append(" linhas) ===\n");
            List<TextLine> lines = page.lines();
            if (page.number() == 1) {
                for (int i = 0; i < lines.size() && i < firstPageLines && sent < maxLines; i++, sent++) {
                    line(sb, lines.get(i));
                }
                if (lines.size() > firstPageLines) {
                    sb.append("... (").append(lines.size() - firstPageLines).append(" linhas omitidas)\n");
                }
                continue;
            }
            int head = Math.min(HEAD_LINES, lines.size());
            for (int i = 0; i < head && sent < maxLines; i++, sent++) {
                line(sb, lines.get(i));
            }
            int tailStart = Math.max(head, lines.size() - TAIL_LINES);
            if (tailStart > head) {
                sb.append("... (").append(tailStart - head).append(" linhas omitidas)\n");
            }
            for (int i = tailStart; i < lines.size() && sent < maxLines; i++, sent++) {
                line(sb, lines.get(i));
            }
        }
        return new Rendered(sb.toString(), sent, total);
    }

    private static void line(StringBuilder sb, TextLine line) {
        sb.append("[p").append(line.page()).append(" l").append(line.number()).append(" y")
                .append(Math.round(line.top())).append("]");
        for (List<TextWord> phrase : MapInterpreter.phrases(line.words())) {
            StringBuilder text = new StringBuilder();
            for (TextWord w : phrase) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(w.text());
            }
            sb.append(" x").append(Math.round(phrase.getFirst().x0())).append(" \"").append(mask(text.toString())
                    .replace("\"", "'")).append('"');
        }
        sb.append('\n');
    }

    /** @param lines linhas enviadas; {@code totalLines} do documento */
    public record Rendered(String text, int lines, int totalLines) {
    }
}
