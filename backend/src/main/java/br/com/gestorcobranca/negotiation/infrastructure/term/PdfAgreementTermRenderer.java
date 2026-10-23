package br.com.gestorcobranca.negotiation.infrastructure.term;

import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Termo de acordo em PDF (PDFBox, fonte Helvetica padrão, A4). O texto é o modelo padrão da
 * plataforma; cláusulas específicas de credor exigem um modelo versionado (pendência registrada).
 * Caracteres fora da codificação WinAnsi são substituídos para que nenhum dado quebre a geração.
 */
@Component
class PdfAgreementTermRenderer implements AgreementTermRenderer {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final float MARGIN = 50;
    private static final float LEADING = 14;
    private static final float BODY_SIZE = 10;

    @Override
    public byte[] render(TermDocument d) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("TERMO DE ACORDO E CONFISSÃO DE DÍVIDA", 14, true));
        lines.add(Line.blank());
        lines.add(Line.text("Proposta: " + d.proposalId()));
        if (d.replacedAgreementId() != null) {
            lines.add(Line.text("Renegociação do acordo: " + d.replacedAgreementId()));
        }
        lines.add(Line.text("Credor: " + d.creditorName()));
        lines.add(Line.text("Devedor(a): " + d.debtorName() + (d.debtorDocument() == null ? "" : " - " + d.debtorDocument())));
        lines.add(Line.text("Canal do aceite: " + (d.channel() == null ? "-" : d.channel())
                + "    Política de negociação: versão " + d.policyVersion()));
        lines.add(Line.blank());
        lines.add(Line.bold("1. Débitos negociados"));
        for (TermTitle t : d.titles()) {
            lines.add(Line.text("   Título " + t.externalId() + "  vencimento " + date(t.dueDate()) + "  valor atualizado "
                    + money(t.updatedAmount())));
        }
        lines.add(Line.blank());
        lines.add(Line.bold("2. Condições"));
        lines.add(Line.text("   Valor atualizado da dívida: " + money(d.updatedAmount())));
        lines.add(Line.text("   Desconto concedido: " + money(d.discountAmount())));
        lines.add(Line.text("   Valor negociado: " + money(d.negotiatedAmount())));
        if (d.financingInterest().signum() > 0) {
            lines.add(Line.text("   Juros do parcelamento: " + money(d.financingInterest())));
        }
        lines.add(Line.text("   Valor total do acordo: " + money(d.totalAmount())));
        lines.add(Line.blank());
        lines.add(Line.bold("3. Pagamentos"));
        for (TermInstallment i : d.schedule()) {
            String label = d.schedule().size() == 1 ? "À vista" : i.number() == 1 ? "Entrada" : "Parcela " + i.number();
            lines.add(Line.text("   " + label + "  vencimento " + date(i.dueDate()) + "  " + money(i.amount())));
        }
        lines.add(Line.blank());
        lines.add(Line.bold("4. Quebra do acordo"));
        wrap(d.breakRule(), 95).forEach(l -> lines.add(Line.text("   " + l)));
        lines.add(Line.blank());
        lines.add(Line.bold("5. Declaração"));
        wrap("O(A) devedor(a) reconhece os débitos acima e aceita as condições deste termo pelo canal indicado. "
                + "A quitação de cada parcela ocorre com a confirmação do pagamento. O pagamento integral extingue os "
                + "débitos negociados, com a aplicação do desconto concedido.", 95)
                .forEach(l -> lines.add(Line.text("   " + l)));
        lines.add(Line.blank());
        lines.add(Line.text("Emitido em " + d.generatedAt().atZone(TimeConfig.BUSINESS_ZONE).format(DATE_TIME)
                + " (horário de Brasília)."));
        return write(lines, d);
    }

    private byte[] write(List<Line> lines, TermDocument d) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Termo de acordo " + d.proposalId());
            info.setProducer("Gestor de Cobrança");
            Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            created.setTimeInMillis(d.generatedAt().toEpochMilli());
            info.setCreationDate(created);
            PDPage page = null;
            PDPageContentStream content = null;
            float y = 0;
            try {
                for (Line line : lines) {
                    if (page == null || y < MARGIN + LEADING) {
                        if (content != null) {
                            content.close();
                        }
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        content = new PDPageContentStream(document, page);
                        y = page.getMediaBox().getHeight() - MARGIN;
                    }
                    if (!line.text().isEmpty()) {
                        content.beginText();
                        content.setFont(line.bold() ? bold : regular, line.size());
                        content.newLineAtOffset(MARGIN, y);
                        content.showText(sanitize(line.text()));
                        content.endText();
                    }
                    y -= line.size() > BODY_SIZE ? LEADING * 1.5f : LEADING;
                }
            } finally {
                if (content != null) {
                    content.close();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gerar o termo de acordo", e);
        }
    }

    private record Line(String text, float size, boolean bold) {
        static Line text(String t) {
            return new Line(t, BODY_SIZE, false);
        }

        static Line bold(String t) {
            return new Line(t, BODY_SIZE, true);
        }

        static Line blank() {
            return new Line("", BODY_SIZE, false);
        }
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (current.length() + word.length() + 1 > width && !current.isEmpty()) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /** Mantém ASCII imprimível e Latin-1 (acentos); troca travessões e demais caracteres. */
    static String sanitize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '–' || c == '—') {
                sb.append('-');
            } else if ((c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF)) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static String money(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(PT_BR).format(value).replace('\u00A0', ' ').replace('\u202F', ' ');
    }

    private static String date(LocalDate date) {
        return date == null ? "-" : date.format(DATE);
    }
}
