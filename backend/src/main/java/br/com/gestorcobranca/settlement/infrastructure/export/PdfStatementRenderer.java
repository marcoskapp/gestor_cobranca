package br.com.gestorcobranca.settlement.infrastructure.export;

import br.com.gestorcobranca.settlement.application.port.out.StatementDocumentRenderer;
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
 * PDF resumo do demonstrativo de prestação de contas (PDFBox, Helvetica, A4): identificação do credor e
 * do período, totais por bloco com valores alinhados à direita, observações (pendências e divergências)
 * e o SHA-256 do arquivo conciliável que acompanha o resumo. Caracteres fora da codificação WinAnsi
 * são substituídos.
 */
@Component
class PdfStatementRenderer implements StatementDocumentRenderer {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final float MARGIN = 50;
    private static final float LEADING = 15;
    private static final float BODY = 10;
    private static final float VALUE_COLUMN = 545;

    private record Row(String text, String value, float size, boolean bold) {
    }

    @Override
    public byte[] render(Summary s) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("DEMONSTRATIVO DE PRESTAÇÃO DE CONTAS", null, 14, true));
        rows.add(new Row("", null, BODY, false));
        rows.add(new Row("Credor: " + s.creditorName() + (s.creditorDocument() == null ? "" : " — CNPJ "
                + s.creditorDocument()), null, BODY, false));
        rows.add(new Row("Demonstrativo nº " + s.sequenceNumber() + " — período de " + date(s.periodStart()) + " a "
                + date(s.periodEnd()) + " — situação " + s.status(), null, BODY, false));
        for (Section section : s.sections()) {
            rows.add(new Row("", null, BODY, false));
            rows.add(new Row(section.title(), null, 11, true));
            for (Line line : section.lines()) {
                rows.add(new Row("   " + line.label(), money(line.amount()), BODY, false));
            }
        }
        if (!s.notes().isEmpty()) {
            rows.add(new Row("", null, BODY, false));
            rows.add(new Row("Observações", null, 11, true));
            for (String note : s.notes()) {
                wrap(note, 95).forEach(l -> rows.add(new Row("   " + l, null, BODY, false)));
            }
        }
        rows.add(new Row("", null, BODY, false));
        if (s.exportSha256() != null) {
            rows.add(new Row("Arquivo conciliável (SHA-256): " + s.exportSha256(), null, 8, false));
        }
        rows.add(new Row("Emitido em " + s.generatedAt().atZone(TimeConfig.BUSINESS_ZONE).format(DATE_TIME)
                + " (horário de Brasília).", null, 8, false));
        return write(rows, s);
    }

    private byte[] write(List<Row> rows, Summary s) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Demonstrativo " + s.sequenceNumber() + " " + s.periodStart() + " a " + s.periodEnd());
            info.setProducer("Gestor de Cobrança");
            Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            created.setTimeInMillis(s.generatedAt().toEpochMilli());
            info.setCreationDate(created);
            PDPage page = null;
            PDPageContentStream content = null;
            float y = 0;
            try {
                for (Row row : rows) {
                    if (page == null || y < MARGIN + LEADING) {
                        if (content != null) {
                            content.close();
                        }
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        content = new PDPageContentStream(document, page);
                        y = page.getMediaBox().getHeight() - MARGIN;
                    }
                    PDType1Font font = row.bold() ? bold : regular;
                    if (!row.text().isEmpty()) {
                        content.beginText();
                        content.setFont(font, row.size());
                        content.newLineAtOffset(MARGIN, y);
                        content.showText(sanitize(row.text()));
                        content.endText();
                    }
                    if (row.value() != null) {
                        String value = sanitize(row.value());
                        float width = font.getStringWidth(value) / 1000 * row.size();
                        content.beginText();
                        content.setFont(font, row.size());
                        content.newLineAtOffset(VALUE_COLUMN - width, y);
                        content.showText(value);
                        content.endText();
                    }
                    y -= row.size() > BODY + 1 ? LEADING * 1.4f : LEADING;
                }
            } finally {
                if (content != null) {
                    content.close();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gerar o PDF do demonstrativo", e);
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
            if (c == '–' || c == '—' || c == '−') {
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
        return NumberFormat.getCurrencyInstance(PT_BR).format(value).replace(' ', ' ').replace(' ', ' ');
    }

    private static String date(LocalDate date) {
        return date == null ? "-" : date.format(DATE);
    }
}
