package br.com.gestorcobranca.ingestion.support.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Gera PDFs <strong>sintéticos</strong> nos testes (nenhum PDF real entra no repositório): texto posicionado em
 * coordenadas com origem no canto superior esquerdo (como o leitor devolve), Helvetica, página A4.
 */
public final class SyntheticPdf {

    public static final float WIDTH = PDRectangle.A4.getWidth();
    public static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private final List<List<Item>> pages = new ArrayList<>();

    private record Item(String text, float x, float top, float size, boolean right) {
    }

    public SyntheticPdf page() {
        pages.add(new ArrayList<>());
        return this;
    }

    /** Texto alinhado à esquerda em {@code x}; {@code top} é o topo da linha. */
    public SyntheticPdf text(float x, float top, float size, String text) {
        current().add(new Item(text, x, top, size, false));
        return this;
    }

    /** Texto alinhado à direita terminando em {@code right}. */
    public SyntheticPdf right(float right, float top, float size, String text) {
        current().add(new Item(text, right, top, size, true));
        return this;
    }

    public static float width(String text, float size) {
        try {
            return FONT.getStringWidth(text) / 1000f * size;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Item> current() {
        if (pages.isEmpty()) {
            page();
        }
        return pages.getLast();
    }

    public byte[] build() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (List<Item> items : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    for (Item item : items) {
                        float x = item.right() ? item.x() - width(item.text(), item.size()) : item.x();
                        float baseline = HEIGHT - item.top() - item.size() * 0.8f;
                        cs.beginText();
                        cs.setFont(FONT, item.size());
                        cs.newLineAtOffset(x, baseline);
                        cs.showText(item.text());
                        cs.endText();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PDF só com imagem (escaneado): o texto é desenhado num bitmap, sem camada de texto. */
    public static byte[] imageOnly(List<String> lines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            int y = 80;
            for (String line : lines) {
                g.drawString(line, 60, y);
                y += 34;
            }
            g.dispose();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.drawImage(xobject, 0, 0, WIDTH, HEIGHT);
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
