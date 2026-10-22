package br.com.gestorcobranca.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Copia um stream para arquivo calculando SHA-256 e tamanho em uma única passagem. */
final class HashingCopy {

    record Result(long size, String sha256) {
    }

    private HashingCopy() {
    }

    static Result copy(InputStream source, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long size;
        try (DigestInputStream in = new DigestInputStream(source, digest);
             OutputStream out = Files.newOutputStream(target)) {
            size = in.transferTo(out);
        }
        return new Result(size, HexFormat.of().formatHex(digest.digest()));
    }
}
