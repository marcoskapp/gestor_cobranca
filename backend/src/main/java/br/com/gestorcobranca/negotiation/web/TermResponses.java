package br.com.gestorcobranca.negotiation.web;

import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.TermContent;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Resposta de download do termo de acordo (PDF, sem cache, com o SHA-256 em cabeçalho). */
final class TermResponses {

    private TermResponses() {
    }

    static ResponseEntity<InputStreamResource> of(TermContent term) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(term.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", term.sha256())
                .contentType(MediaType.APPLICATION_PDF);
        if (term.size() >= 0) {
            builder.contentLength(term.size());
        }
        return builder.body(new InputStreamResource(term.content()));
    }
}
