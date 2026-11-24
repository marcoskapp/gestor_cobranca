package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Teto do corpo dos envios com arquivos do portal do credor, conferido <b>antes</b> de o multipart ser lido (o limite
 * geral da aplicação é de 105 MB): acima de 5 documentos de 10 MB mais folga → 413; multipart sem
 * {@code Content-Length} → 411. Roda depois da autenticação (ordem padrão dos filtros da aplicação), então só alcança
 * a pessoa do credor já autenticada (SEG-F01 da fatia 5).
 */
@Component
class CreditorRequestSizeFilter extends OncePerRequestFilter {

    static final long MAX_MULTIPART_BYTES = CreditorDisputeController.MAX_DOCUMENTS
            * CreditorDisputeController.MAX_DOCUMENT_BYTES + 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return !request.getRequestURI().startsWith(request.getContextPath() + ApiPaths.CREDITOR + "/")
                || contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            reject(response, HttpStatus.LENGTH_REQUIRED, "request.length_required",
                    "Envio de arquivos sem o tamanho informado. Tente de novo.");
            return;
        }
        if (length > MAX_MULTIPART_BYTES) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "request.too_large",
                    "Envio grande demais: no máximo %d documentos de até 10 MB cada."
                            .formatted(CreditorDisputeController.MAX_DOCUMENTS));
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setHeader("Connection", "close");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\"}"
                .formatted(status.getReasonPhrase(), status.value(), code, detail));
    }
}
