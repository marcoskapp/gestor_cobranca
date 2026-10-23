package br.com.gestorcobranca.legal.domain.service;

import java.util.Optional;

/**
 * Tipo do arquivo conferido pelo conteúdo (assinatura), nunca pelo nome ou pelo tipo declarado:
 * documentos do jurídico aceitam PDF, PNG e JPEG.
 */
public final class FileSignature {

    public static final int HEADER_BYTES = 8;

    private FileSignature() {
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
}
