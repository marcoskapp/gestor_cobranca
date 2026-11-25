package br.com.gestorcobranca.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** PDFs válidos (abrem no PDFBox) para os testes de envio de documentos; nenhum dado real. */
public final class TestPdfs {

    private TestPdfs() {
    }

    /** PDF de uma página em branco. */
    public static byte[] blank() {
        return build(null, false);
    }

    /** PDF com o texto no título do documento (ex.: o marcador do verificador de malware de teste). */
    public static byte[] withTitle(String title) {
        return build(title, false);
    }

    /** PDF que executa JavaScript ao abrir (deve ser recusado). */
    public static byte[] withJavaScript() {
        return build(null, true);
    }

    private static byte[] build(String title, boolean javaScript) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if (title != null) {
                document.getDocumentInformation().setTitle(title);
            }
            if (javaScript) {
                document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('x');"));
            }
            // sem compressão de objetos: o texto do título fica legível no arquivo (verificador de teste)
            document.save(out, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
