package br.com.gestorcobranca.shared.storage;

import br.com.gestorcobranca.support.TestPdfs;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Conteúdo ativo em PDF recebido de fora (RS-39 do portal do credor): só o PDF sem nada ativo passa. */
class PdfActiveContentTest {

    @Test
    void pdfSimplesPassa() {
        assertThat(PdfActiveContent.inspect(TestPdfs.blank())).isEqualTo(PdfActiveContent.Verdict.SAFE);
    }

    @Test
    void javaScriptAcaoDeAbrirProgramaEArquivoEmbutidoSaoRecusados() throws Exception {
        assertThat(PdfActiveContent.inspect(TestPdfs.withJavaScript())).isEqualTo(PdfActiveContent.Verdict.ACTIVE_CONTENT);
        assertThat(PdfActiveContent.inspect(build(d -> {
            PDActionLaunch launch = new PDActionLaunch();
            launch.setFile(new PDComplexFileSpecification());
            d.getDocumentCatalog().setOpenAction(launch);
        }))).isEqualTo(PdfActiveContent.Verdict.ACTIVE_CONTENT);
        assertThat(PdfActiveContent.inspect(build(d -> {
            try {
                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                spec.setFile("anexo.exe");
                spec.setEmbeddedFile(new PDEmbeddedFile(d, new ByteArrayInputStream("MZ".getBytes(StandardCharsets.US_ASCII))));
                PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
                tree.setNames(Map.of("anexo.exe", spec));
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(d.getDocumentCatalog());
                names.setEmbeddedFiles(tree);
                d.getDocumentCatalog().setNames(names);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }))).isEqualTo(PdfActiveContent.Verdict.ACTIVE_CONTENT);
    }

    @Test
    void anotacaoDeAnexoOuMidiaERecusadaMesmoSemArquivo() throws Exception {
        for (String subtype : java.util.List.of("FileAttachment", "RichMedia", "Movie", "Sound", "Screen", "3D")) {
            assertThat(PdfActiveContent.inspect(build(d -> page(d, annotation(subtype)))))
                    .as(subtype).isEqualTo(PdfActiveContent.Verdict.ACTIVE_CONTENT);
        }
    }

    @Test
    void especificacaoComArquivoEmbutidoSemTypeERecusada() throws Exception {
        assertThat(PdfActiveContent.inspect(build(d -> {
            org.apache.pdfbox.cos.COSDictionary spec = new org.apache.pdfbox.cos.COSDictionary();
            org.apache.pdfbox.cos.COSDictionary ef = new org.apache.pdfbox.cos.COSDictionary();
            ef.setItem(org.apache.pdfbox.cos.COSName.F, new org.apache.pdfbox.cos.COSStream());
            spec.setItem(org.apache.pdfbox.cos.COSName.EF, ef);
            d.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("X"), spec);
        }))).isEqualTo(PdfActiveContent.Verdict.ACTIVE_CONTENT);
    }

    @Test
    void comprovanteComLinkPassa() throws Exception {
        assertThat(PdfActiveContent.inspect(build(d -> {
            org.apache.pdfbox.cos.COSDictionary link = annotation("Link");
            org.apache.pdfbox.cos.COSDictionary uri = new org.apache.pdfbox.cos.COSDictionary();
            uri.setItem(org.apache.pdfbox.cos.COSName.S, org.apache.pdfbox.cos.COSName.URI);
            uri.setString(org.apache.pdfbox.cos.COSName.URI, "https://banco.example/comprovante");
            link.setItem(org.apache.pdfbox.cos.COSName.A, uri);
            page(d, link);
        }))).isEqualTo(PdfActiveContent.Verdict.SAFE);
    }

    private static org.apache.pdfbox.cos.COSDictionary annotation(String subtype) {
        org.apache.pdfbox.cos.COSDictionary annotation = new org.apache.pdfbox.cos.COSDictionary();
        annotation.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE, org.apache.pdfbox.cos.COSName.getPDFName(subtype));
        return annotation;
    }

    private static void page(PDDocument d, org.apache.pdfbox.cos.COSDictionary annotation) {
        org.apache.pdfbox.cos.COSArray annots = new org.apache.pdfbox.cos.COSArray();
        annots.add(annotation);
        d.getPage(0).getCOSObject().setItem(org.apache.pdfbox.cos.COSName.ANNOTS, annots);
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void semVagaDeLeituraNoPrazoResponde503EmVezDeEsperarParaSempre() {
        int permits = PdfActiveContent.MAX_CONCURRENT_PARSES;
        PdfActiveContent.PARSES.acquireUninterruptibly(permits);
        try {
            long start = System.nanoTime();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> PdfActiveContent.inspect(TestPdfs.blank()))
                    .isInstanceOf(br.com.gestorcobranca.shared.domain.TemporarilyUnavailableException.class)
                    .hasFieldOrPropertyWithValue("code", "storage.pdf_busy");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(java.time.Duration.ofSeconds(PdfActiveContent.PARSE_WAIT_SECONDS + 3));
        } finally {
            PdfActiveContent.PARSES.release(permits);
        }
        assertThat(PdfActiveContent.inspect(TestPdfs.blank())).isEqualTo(PdfActiveContent.Verdict.SAFE);
    }

    @Test
    void arquivoQueNaoAbreEhRecusado() {
        assertThat(PdfActiveContent.inspect("%PDF-1.4 sem estrutura".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(PdfActiveContent.Verdict.UNREADABLE);
    }

    private static byte[] build(Consumer<PDDocument> customize) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            customize.accept(document);
            document.save(out);
            return out.toByteArray();
        }
    }
}
