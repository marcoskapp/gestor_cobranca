package br.com.gestorcobranca.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;

/**
 * Tipo do arquivo conferido pelo <b>conteúdo</b> (assinatura nos primeiros bytes), nunca só pelo nome ou pelo tipo
 * declarado. Lista branca de documentos e imagens aceitos em uploads de evidência que alteram valores ou vêm de
 * sistemas externos: PDF, PNG e JPEG.
 */
public final class FileSignatures {

    public static final int HEADER_BYTES = 8;
    public static final Set<String> DOCUMENT_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

    private FileSignatures() {
    }

    public static Optional<String> detect(byte[] head, int length) {
        if (length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') {
            return Optional.of("application/pdf");
        }
        if (length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return Optional.of("image/png");
        }
        if (length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        return Optional.empty();
    }

    /**
     * Lê a assinatura sem consumir o conteúdo.
     *
     * @return o tipo detectado e o fluxo completo para gravar
     */
    public static Sniffed sniff(InputStream content) {
        try {
            PushbackInputStream in = new PushbackInputStream(content, HEADER_BYTES);
            byte[] head = new byte[HEADER_BYTES];
            int read = in.readNBytes(head, 0, HEADER_BYTES);
            if (read > 0) {
                in.unread(head, 0, read);
            }
            return new Sniffed(detect(head, read), in);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    /** @param contentType tipo detectado (vazio = fora da lista branca) */
    public record Sniffed(Optional<String> contentType, InputStream content) {
    }
}
