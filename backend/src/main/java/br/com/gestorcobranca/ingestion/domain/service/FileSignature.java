package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;

/**
 * Confere o conteúdo do arquivo contra o formato declarado (extensão/layout), pelos primeiros
 * bytes — a extensão e o {@code Content-Type} são informados pelo cliente e não bastam:
 * <ul>
 *   <li>XLSX: pacote ZIP ({@code PK\u0003\u0004});</li>
 *   <li>CSV: texto — sem bytes nulos e sem assinatura de ZIP, PDF, executável ou planilha antiga
 *       (OLE2/XLS).</li>
 * </ul>
 */
public final class FileSignature {

    /** Bytes inspecionados no início do arquivo. */
    public static final int HEAD_SIZE = 8192;

    private FileSignature() {
    }

    public static void check(ImportFormat format, byte[] head) {
        if (head.length == 0) {
            throw new ImportFileException("import.file_empty", "Arquivo vazio");
        }
        switch (format) {
            case XLSX -> {
                if (!startsWith(head, 0x50, 0x4B, 0x03, 0x04)) {
                    throw new ImportFileException("import.file_type_invalid", "Conteúdo não é uma planilha XLSX");
                }
            }
            case CSV -> {
                if (startsWith(head, 0x50, 0x4B) || startsWith(head, 0x25, 0x50, 0x44, 0x46)
                        || startsWith(head, 0x4D, 0x5A) || startsWith(head, 0xD0, 0xCF, 0x11, 0xE0)
                        || startsWith(head, 0x7F, 0x45, 0x4C, 0x46) || containsNul(head)) {
                    throw new ImportFileException("import.file_type_invalid", "Conteúdo não é um arquivo de texto CSV");
                }
            }
            case JSON -> {
                // registros da API: gerados pela própria aplicação
            }
        }
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNul(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }
}
