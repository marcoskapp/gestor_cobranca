package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSource;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.LabelField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * "Impressão digital" de um layout de PDF: rótulos do cabeçalho da tabela (na ordem) e rótulos do cabeçalho do
 * devedor, normalizados. Identifica o mapa salvo de um credor ({@code pdf_layout_template}) e decide se um PDF novo é
 * do mesmo formato — então o mapa é aplicado direto, sem IA.
 */
public final class LayoutFingerprint {

    private LayoutFingerprint() {
    }

    /** SHA-256 (hex) dos rótulos que definem o layout. */
    public static String of(ExtractionMap map) {
        StringBuilder sb = new StringBuilder("v").append(ExtractionMap.SCHEMA_VERSION);
        if (map.table() != null) {
            for (List<String> line : map.table().headerLines()) {
                sb.append("|h:");
                line.forEach(l -> sb.append(TextMatch.normalize(l)).append('\u001f'));
            }
        }
        sb.append("|d:").append(map.debtor().source());
        if (map.debtor().blockStartLabel() != null) {
            sb.append(':').append(TextMatch.normalize(map.debtor().blockStartLabel()));
        }
        TreeSet<String> labels = new TreeSet<>();
        map.debtor().fields().forEach(f -> labels.add(TextMatch.normalize(f.label())));
        labels.forEach(l -> sb.append('\u001f').append(l));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * O documento é do layout do mapa: alguma linha contém todos os rótulos da 1ª linha do cabeçalho (na ordem) e, com
     * cabeçalho de devedor, o rótulo de abertura do bloco e os rótulos dos campos aparecem no texto.
     */
    public static boolean matches(PdfText text, ExtractionMap map) {
        if (map.table() == null || map.table().headerLines().isEmpty()) {
            return false;
        }
        List<String> header = map.table().headerLines().getFirst();
        List<TextLine> lines = text.lines();
        boolean headerFound = lines.stream()
                .anyMatch(l -> MapInterpreter.zonesFromHeader(l, header, List.of()) != null);
        if (!headerFound) {
            return false;
        }
        if (map.debtor().source() != DebtorSource.HEADER) {
            return true;
        }
        if (lines.stream().noneMatch(l -> TextMatch.contains(l, map.debtor().blockStartLabel()))) {
            return false;
        }
        for (LabelField f : map.debtor().fields()) {
            if (lines.stream().noneMatch(l -> TextMatch.contains(l, f.label()))) {
                return false;
            }
        }
        return true;
    }
}
