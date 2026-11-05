package br.com.gestorcobranca.shared.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSignaturesAndClamdTest {

    @Test
    void typeComesFromTheContentAndTheStreamIsPreserved() throws Exception {
        byte[] pdf = "%PDF-1.7 conteudo".getBytes(StandardCharsets.US_ASCII);
        FileSignatures.Sniffed sniffed = FileSignatures.sniff(new ByteArrayInputStream(pdf));
        assertThat(sniffed.contentType()).contains("application/pdf");
        assertThat(sniffed.content().readAllBytes()).isEqualTo(pdf);
        // extensão .pdf com conteúdo de outro tipo: recusado
        assertThat(FileSignatures.sniff(new ByteArrayInputStream("MZ executavel".getBytes(StandardCharsets.US_ASCII)))
                .contentType()).isEmpty();
        assertThat(FileSignatures.sniff(new ByteArrayInputStream(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .contentType()).contains("image/jpeg");
    }

    @Test
    void clamdRepliesAreParsedAndUnknownRepliesAreTransient() {
        assertThat(ClamdMalwareScanner.parse("stream: OK").verdict()).isEqualTo(MalwareScanner.Verdict.CLEAN);
        MalwareScanner.ScanResult found = ClamdMalwareScanner.parse("stream: Win.Test.EICAR_HDB-1 FOUND");
        assertThat(found.verdict()).isEqualTo(MalwareScanner.Verdict.INFECTED);
        assertThat(found.signature()).isEqualTo("Win.Test.EICAR_HDB-1");
        assertThatThrownBy(() -> ClamdMalwareScanner.parse("INSTREAM size limit exceeded. ERROR"))
                .isInstanceOf(MalwareScanner.MalwareScanUnavailableException.class);
    }

    @Test
    void unreachableDaemonIsTransient() {
        ClamdMalwareScanner scanner = new ClamdMalwareScanner("127.0.0.1", 1, java.time.Duration.ofMillis(300));
        assertThatThrownBy(() -> scanner.scan(new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(MalwareScanner.MalwareScanUnavailableException.class);
    }
}
