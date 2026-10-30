package br.com.gestorcobranca.sandbox.infrastructure.pdf;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.DemoPdfRenderer;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.History;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.HistoryTitle;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.Table;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.TableRow;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdfLayouts;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Desenha os PDFs fictícios de demonstração com o PDFBox: A4, Helvetica, texto posicionado como num relatório de ERP
 * (valores alinhados à direita sob o rótulo da coluna, título do histórico em duas linhas, quebra de página com o topo
 * e o cabeçalho da tabela repetidos). Pública para o teste de compatibilidade com o leitor da Importação PDF.
 */
@SandboxSeedComponent
public class PdfBoxDemoPdfRenderer implements DemoPdfRenderer {

    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final float DATA = 7f;
    private static final float LABEL = 8f;
    private static final float LAST_LINE = 770f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Início de cada coluna da 1ª linha do histórico ({@link DemoPdfLayouts#HISTORY_LINE_1}). */
    private static final float[] H1_X = {30, 50, 85, 108, 150, 195, 222, 290, 330, 385, 430, 495};
    private static final float[] TABLE_X = {30, 110, 260, 320, 370, 470};

    private record Item(String text, float x, float top, float size, boolean right) {
    }

    /** Páginas em construção; o rodapé "Página x de n" entra no fim, quando n é conhecido. */
    private static final class Pages {
        final List<List<Item>> pages = new ArrayList<>();

        Pages page() {
            pages.add(new ArrayList<>());
            return this;
        }

        Pages text(float x, float top, float size, String text) {
            pages.getLast().add(new Item(text, x, top, size, false));
            return this;
        }

        Pages right(float right, float top, float size, String text) {
            pages.getLast().add(new Item(text, right, top, size, true));
            return this;
        }
    }

    public PdfBoxDemoPdfRenderer() {
        // sem estado
    }

    @Override
    public byte[] render(DemoPdf.Document document) {
        Pages pages = switch (document) {
            case History h -> history(h);
            case Table t -> table(t);
        };
        for (int i = 0; i < pages.pages.size(); i++) {
            pages.pages.get(i).add(new Item(DemoPdfLayouts.PAGE_FOOTER + " " + (i + 1) + " de " + pages.pages.size(),
                    400, 820, LABEL, false));
        }
        return build(pages);
    }

    // ------------------------------------------------------------------ histórico financeiro

    private static float right(int column) {
        return H1_X[column] + width(DemoPdfLayouts.HISTORY_LINE_1.get(column), LABEL);
    }

    private static void historyTop(Pages pdf, History h, boolean withDebtor) {
        pdf.text(30, 20, LABEL, "Data Emissão: " + DATE.format(h.issuedOn()));
        pdf.text(220, 18, 10, DemoPdfLayouts.HISTORY_HEADING);
        pdf.text(480, 20, LABEL, "Hora Emissão: " + h.issuedAt());
        if (withDebtor) {
            var d = h.debtor();
            pdf.text(30, 40, LABEL, "Cliente:").text(70, 40, LABEL, d.code() + " " + d.name())
                    .text(300, 40, LABEL, "CPF/CNPJ:").text(345, 40, LABEL, d.document());
            pdf.text(30, 52, LABEL, "Endereço:").text(70, 52, LABEL, d.street())
                    .text(300, 52, LABEL, "Bairro:").text(345, 52, LABEL, d.district());
            pdf.text(30, 64, LABEL, "Telefone:").text(180, 64, LABEL, d.phone())
                    .text(300, 64, LABEL, "Cidade:").text(345, 64, LABEL, d.city());
            pdf.text(30, 76, LABEL, "Email:").text(70, 76, LABEL, d.email());
        }
        float top = withDebtor ? 92 : 40;
        for (int c = 0; c < H1_X.length; c++) {
            pdf.text(H1_X[c], top, LABEL, DemoPdfLayouts.HISTORY_LINE_1.get(c));
        }
        pdf.text(H1_X[1], top + 9, LABEL, DemoPdfLayouts.HISTORY_LINE_2);
    }

    private static Pages history(History h) {
        Pages pdf = new Pages().page();
        historyTop(pdf, h, true);
        float y = 115;
        for (HistoryTitle t : h.titles()) {
            if (y > LAST_LINE) {
                pdf.page();
                historyTop(pdf, h, false);
                y = 55;
            }
            pdf.right(right(0), y, DATA, t.company()).text(H1_X[1], y, DATA, t.number())
                    .text(H1_X[2], y, DATA, t.installment()).text(H1_X[3], y, DATA, DATE.format(t.issue()))
                    .text(H1_X[4], y, DATA, DATE.format(t.due()))
                    .right(right(6), y, DATA, String.valueOf(t.delay())).right(right(7), y, DATA, brl(t.original()))
                    .right(right(8), y, DATA, brl(t.pending())).right(right(9), y, DATA, brl(t.interest()))
                    .text(H1_X[10], y, DATA, t.payment()).text(H1_X[11], y, DATA, t.ourNumber());
            pdf.text(H1_X[1], y + 9, DATA, t.account());
            y += 21;
        }
        if (y > LAST_LINE - 30) {
            pdf.page();
            y = 40;
        }
        pdf.text(190, y + 4, DATA, DemoPdfLayouts.HISTORY_TOTALS).right(right(7), y + 4, DATA, brl(h.printedOriginal()))
                .right(right(8), y + 4, DATA, brl(h.printedPending()))
                .right(right(9), y + 4, DATA, brl(h.printedInterest()));
        pdf.text(100, y + 30, LABEL, "Nº de Títulos:").right(220, y + 30, LABEL, String.valueOf(h.titles().size()))
                .text(260, y + 30, LABEL, "Títulos Vencidos:").right(420, y + 30, LABEL, brl(h.printedPending()));
        return pdf;
    }

    // ------------------------------------------------------------------ tabela simples

    private static void tableHeader(Pages pdf, float top) {
        for (int c = 0; c < TABLE_X.length; c++) {
            pdf.text(TABLE_X[c], top, LABEL, DemoPdfLayouts.TABLE_HEADER.get(c));
        }
    }

    private static Pages table(Table t) {
        Pages pdf = new Pages().page();
        pdf.text(30, 20, 10, t.heading()).text(400, 22, LABEL, DemoPdfLayouts.TABLE_POSITION + " "
                + DATE.format(t.position()));
        tableHeader(pdf, 45);
        float valueRight = TABLE_X[5] + width("Valor", LABEL) + 20;
        float y = 58;
        for (TableRow r : t.rows()) {
            if (y > LAST_LINE) {
                pdf.page();
                tableHeader(pdf, 40);
                y = 53;
            }
            pdf.text(TABLE_X[0], y, DATA, r.document()).text(TABLE_X[1], y, DATA, r.name())
                    .text(TABLE_X[2], y, DATA, r.contract()).text(TABLE_X[3], y, DATA, String.valueOf(r.installment()))
                    .text(TABLE_X[4], y, DATA, DATE.format(r.due())).right(valueRight, y, DATA, plain(r.amount()));
            y += 11;
        }
        pdf.text(30, y + 6, LABEL, DemoPdfLayouts.TABLE_TOTAL).text(70, y + 6, LABEL, brl(t.total()));
        pdf.text(30, y + 18, LABEL, DemoPdfLayouts.TABLE_COUNT).text(85, y + 18, LABEL, String.valueOf(t.rows().size()));
        return pdf;
    }

    // ------------------------------------------------------------------ PDFBox

    private static byte[] build(Pages pages) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Documento FICTÍCIO de demonstração (sandbox do Gestor de Cobrança)");
            info.setProducer("Gestor de Cobrança — sandbox");
            document.setDocumentInformation(info);
            for (List<Item> items : pages.pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    for (Item item : items) {
                        float x = item.right() ? item.x() - width(item.text(), item.size()) : item.x();
                        cs.beginText();
                        cs.setFont(FONT, item.size());
                        cs.newLineAtOffset(x, HEIGHT - item.top() - item.size() * 0.8f);
                        cs.showText(item.text());
                        cs.endText();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível gerar o PDF de demonstração", e);
        }
    }

    private static float width(String text, float size) {
        try {
            return FONT.getStringWidth(text) / 1000f * size;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DecimalFormat format() {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
    }

    static String brl(BigDecimal value) {
        return "R$ " + plain(value);
    }

    static String plain(BigDecimal value) {
        return format().format(value.setScale(2, RoundingMode.HALF_EVEN));
    }
}
