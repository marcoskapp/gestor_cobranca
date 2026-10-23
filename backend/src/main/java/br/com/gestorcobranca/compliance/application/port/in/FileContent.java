package br.com.gestorcobranca.compliance.application.port.in;

import java.io.InputStream;

/**
 * Arquivo entregue para download (evidência, dados do titular, dossiê). O chamador deve fechar
 * o stream.
 *
 * @param size tamanho em bytes, ou -1 se desconhecido
 */
public record FileContent(String fileName, String contentType, long size, InputStream content) {
}
