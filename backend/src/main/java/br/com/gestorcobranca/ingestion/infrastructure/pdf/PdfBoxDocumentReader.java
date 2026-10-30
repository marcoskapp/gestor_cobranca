package br.com.gestorcobranca.ingestion.infrastructure.pdf;

import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextPage;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextWord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leitor de PDF com o Apache PDFBox.
 * <ul>
 *   <li><strong>Abertura segura</strong>: assinatura {@code %PDF-} conferida pelo conteúdo; PDF que pede senha →
 *       {@code pdf_import.password_protected}; qualquer ação ou script JavaScript (catálogo, páginas, anotações,
 *       formulários — busca em todos os objetos) → {@code pdf_import.javascript}; arquivo que o parser não abre →
 *       {@code pdf_import.malformed}; páginas acima do limite → {@code pdf_import.too_many_pages}. Nada é executado:
 *       o PDFBox não interpreta JavaScript; a recusa evita guardar e mostrar conteúdo ativo;</li>
 *   <li><strong>Texto posicionado</strong>: palavras do {@link PDFTextStripper} (ordem por posição) com a caixa em
 *       pontos, origem no canto superior esquerdo; linhas reconstruídas pela linha de base (tolerância de ~45% da
 *       altura da letra);</li>
 *   <li><strong>Imagem</strong>: página renderizada em PNG (tela de validação e leitura visual).</li>
 * </ul>
 */
@Component
class PdfBoxDocumentReader implements PdfDocumentReader {

    private static final Logger LOG = LoggerFactory.getLogger(PdfBoxDocumentReader.class);
    private static final byte[] SIGNATURE = {0x25, 0x50, 0x44, 0x46, 0x2D};
    private static final int SIGNATURE_WINDOW = 1024;
    private static final double BASELINE_TOLERANCE = 0.45;

    @Override
    public PdfInspection inspect(byte[] content, int maxPages) {
        requireSignature(content);
        try (PDDocument document = open(content)) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new ImportFileException("pdf_import.malformed", "O PDF não tem páginas");
            }
            if (pages > maxPages) {
                throw new ImportFileException("pdf_import.too_many_pages",
                        "O PDF tem %d páginas; o limite é %d".formatted(pages, maxPages));
            }
            if (hasJavaScript(document)) {
                throw new ImportFileException("pdf_import.javascript",
                        "O PDF contém JavaScript e foi recusado por segurança. Gere o arquivo de novo sem scripts "
                                + "(\"Imprimir em PDF\" costuma resolver)");
            }
            return new PdfInspection(pages, document.isEncrypted(), text(document));
        } catch (IOException e) {
            throw malformed(e);
        }
    }

    @Override
    public PdfText readText(byte[] content) {
        try (PDDocument document = open(content)) {
            return text(document);
        } catch (IOException e) {
            throw malformed(e);
        }
    }

    @Override
    public RenderedPage render(byte[] content, int page, float dpi) {
        try (PDDocument document = open(content)) {
            if (page < 1 || page > document.getNumberOfPages()) {
                throw new ImportFileException("pdf_import.page_not_found", "Página inexistente");
            }
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(page - 1, dpi, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            double[] size = size(document.getPage(page - 1));
            return new RenderedPage(page, image.getWidth(), image.getHeight(), size[0], size[1], out.toByteArray());
        } catch (IOException e) {
            throw malformed(e);
        }
    }

    // ------------------------------------------------------------------ abertura

    private static void requireSignature(byte[] content) {
        if (content == null || content.length == 0) {
            throw new ImportFileException("import.file_empty", "Arquivo vazio");
        }
        int limit = Math.min(content.length - SIGNATURE.length, SIGNATURE_WINDOW);
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int k = 0; k < SIGNATURE.length; k++) {
                if (content[i + k] != SIGNATURE[k]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return;
            }
        }
        throw new ImportFileException("import.file_type_invalid", "O conteúdo do arquivo não é um PDF");
    }

    private static PDDocument open(byte[] content) throws IOException {
        try {
            return Loader.loadPDF(content);
        } catch (InvalidPasswordException e) {
            throw new ImportFileException("pdf_import.password_protected",
                    "O PDF está protegido por senha. Peça ao credor o arquivo sem senha");
        }
    }

    private static ImportFileException malformed(IOException e) {
        LOG.atWarn().addKeyValue("error", e.getClass().getSimpleName()).log("PDF recusado: estrutura inválida");
        return new ImportFileException("pdf_import.malformed",
                "Não foi possível ler o PDF (arquivo corrompido ou incompleto). Gere o arquivo de novo");
    }

    /** Procura {@code /JS} ou {@code /S /JavaScript} em qualquer dicionário do documento. */
    static boolean hasJavaScript(PDDocument document) throws IOException {
        if (document.getDocumentCatalog().getNames() != null
                && document.getDocumentCatalog().getNames().getJavaScript() != null) {
            return true;
        }
        Deque<COSBase> pending = new ArrayDeque<>();
        Set<COSBase> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(document.getDocumentCatalog().getCOSObject());
        for (COSObjectKey key : document.getDocument().getXrefTable().keySet()) {
            COSObject object = document.getDocument().getObjectFromPool(key);
            if (object != null) {
                pending.add(object);
            }
        }
        while (!pending.isEmpty()) {
            COSBase base = pending.pop();
            if (base instanceof COSObject indirect) {
                base = indirect.getObject();
            }
            if (base == null || !seen.add(base)) {
                continue;
            }
            if (base instanceof COSDictionary dict) {
                if (dict.containsKey(COSName.JS) || COSName.JAVA_SCRIPT.equals(dict.getCOSName(COSName.S))) {
                    return true;
                }
                for (Map.Entry<COSName, COSBase> e : dict.entrySet()) {
                    // filhos indiretos já estão na fila pela tabela de referências; diretos são percorridos aqui
                    if (!(e.getValue() instanceof COSObject)) {
                        pending.push(e.getValue());
                    }
                }
            } else if (base instanceof COSArray array) {
                for (COSBase item : array) {
                    if (!(item instanceof COSObject)) {
                        pending.push(item);
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ texto

    private static PdfText text(PDDocument document) throws IOException {
        WordCollector collector = new WordCollector();
        List<TextPage> pages = new ArrayList<>();
        for (int p = 1; p <= document.getNumberOfPages(); p++) {
            collector.words.clear();
            collector.setStartPage(p);
            collector.setEndPage(p);
            collector.getText(document);
            double[] size = size(document.getPage(p - 1));
            pages.add(new TextPage(p, size[0], size[1], lines(p, collector.words)));
        }
        return new PdfText(pages);
    }

    private static double[] size(PDPage page) {
        PDRectangle box = page.getCropBox();
        int rotation = page.getRotation();
        return rotation % 180 == 0 ? new double[]{box.getWidth(), box.getHeight()}
                : new double[]{box.getHeight(), box.getWidth()};
    }

    /** Agrupa as palavras em linhas pela linha de base e ordena da esquerda para a direita. */
    static List<TextLine> lines(int page, List<TextWord> words) {
        List<TextWord> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(TextWord::bottom).thenComparingDouble(TextWord::x0));
        List<List<TextWord>> groups = new ArrayList<>();
        List<TextWord> current = new ArrayList<>();
        double baseline = 0;
        double height = 0;
        for (TextWord w : sorted) {
            if (!current.isEmpty()) {
                double tolerance = Math.max(1.0, BASELINE_TOLERANCE * Math.min(height, Math.max(w.height(), 0.1)));
                if (Math.abs(w.bottom() - baseline) > tolerance) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(w);
            baseline = current.stream().mapToDouble(TextWord::bottom).average().orElse(w.bottom());
            height = current.stream().mapToDouble(TextWord::height).filter(h -> h > 0).min().orElse(w.height());
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        List<TextLine> out = new ArrayList<>();
        int number = 1;
        for (List<TextWord> g : groups) {
            g.sort(Comparator.comparingDouble(TextWord::x0));
            out.add(new TextLine(page, number++, g));
        }
        return out;
    }

    /** Recebe cada palavra do {@link PDFTextStripper} com as posições dos caracteres. */
    private static final class WordCollector extends PDFTextStripper {

        final List<TextWord> words = new ArrayList<>();

        WordCollector() {
            super();
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            StringBuilder sb = new StringBuilder();
            List<TextPosition> group = new ArrayList<>();
            for (TextPosition t : positions) {
                String unicode = t.getUnicode();
                if (unicode == null || unicode.isBlank()) {
                    flush(sb, group);
                    continue;
                }
                sb.append(unicode);
                group.add(t);
            }
            flush(sb, group);
        }

        private void flush(StringBuilder sb, List<TextPosition> group) {
            if (!group.isEmpty() && !sb.toString().isBlank()) {
                TextPosition first = group.getFirst();
                double x0 = first.getXDirAdj();
                double x1 = x0;
                double top = Double.MAX_VALUE;
                double bottom = -Double.MAX_VALUE;
                for (TextPosition t : group) {
                    x0 = Math.min(x0, t.getXDirAdj());
                    x1 = Math.max(x1, t.getXDirAdj() + t.getWidthDirAdj());
                    bottom = Math.max(bottom, t.getYDirAdj());
                    top = Math.min(top, t.getYDirAdj() - t.getHeightDir());
                }
                words.add(new TextWord(sb.toString().strip(), x0, x1, top, bottom));
            }
            sb.setLength(0);
            group.clear();
        }
    }
}
