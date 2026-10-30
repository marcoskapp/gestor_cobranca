package br.com.gestorcobranca.ingestion.infrastructure.pdf;

import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader.RenderedPage;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.support.pdf.PdfFixtures;
import br.com.gestorcobranca.ingestion.support.pdf.SyntheticPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Abertura segura do PDF: recusas com código e mensagem claros. */
class PdfBoxDocumentReaderTest {

    private final PdfBoxDocumentReader reader = new PdfBoxDocumentReader();
    private final byte[] valid = PdfFixtures.simpleTable(PdfFixtures.simpleTitles());

    private static byte[] modify(byte[] pdf, PdfChange change) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            change.apply(doc);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private interface PdfChange {
        void apply(PDDocument doc) throws IOException;
    }

    @Test
    void rejectsPasswordProtected() throws IOException {
        byte[] locked = modify(valid, doc -> {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("dono", "usuario", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
        });
        assertThatThrownBy(() -> reader.inspect(locked, 50)).isInstanceOf(ImportFileException.class)
                .hasFieldOrPropertyWithValue("code", "pdf_import.password_protected");
    }

    @Test
    void acceptsOwnerPasswordOnly() throws IOException {
        byte[] restricted = modify(valid, doc -> {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("dono", "", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
        });
        var inspection = reader.inspect(restricted, 50);
        assertThat(inspection.encrypted()).isTrue();
        assertThat(inspection.text().hasTextLayer()).isTrue();
    }

    @Test
    void rejectsJavaScript() throws IOException {
        byte[] withScript = modify(valid, doc -> doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('x')")));
        assertThatThrownBy(() -> reader.inspect(withScript, 50)).isInstanceOf(ImportFileException.class)
                .hasFieldOrPropertyWithValue("code", "pdf_import.javascript");
    }

    @Test
    void rejectsMalformedAndNonPdf() {
        byte[] broken = "%PDF-1.7\n1 0 obj << /Type /Catalog >> trailer garbage".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> reader.inspect(broken, 50)).isInstanceOf(ImportFileException.class)
                .hasFieldOrPropertyWithValue("code", "pdf_import.malformed");
        assertThatThrownBy(() -> reader.inspect("documento;nome\n".getBytes(StandardCharsets.UTF_8), 50))
                .isInstanceOf(ImportFileException.class).hasFieldOrPropertyWithValue("code", "import.file_type_invalid");
    }

    @Test
    void rejectsTooManyPages() {
        SyntheticPdf pdf = new SyntheticPdf();
        for (int i = 0; i < 4; i++) {
            pdf.page().text(30, 30, 10, "Página " + i);
        }
        byte[] four = pdf.build();
        assertThatThrownBy(() -> reader.inspect(four, 3)).isInstanceOf(ImportFileException.class)
                .hasFieldOrPropertyWithValue("code", "pdf_import.too_many_pages");
        assertThat(reader.inspect(four, 4).pages()).isEqualTo(4);
    }

    @Test
    void rendersPageAsPng() {
        RenderedPage page = reader.render(valid, 1, 72);
        assertThat(page.png()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(page.widthPx()).isBetween(590, 600);
        assertThat(page.widthPt()).isEqualTo(SyntheticPdf.WIDTH, org.assertj.core.data.Offset.offset(0.5));
    }
}
