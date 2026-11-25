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
