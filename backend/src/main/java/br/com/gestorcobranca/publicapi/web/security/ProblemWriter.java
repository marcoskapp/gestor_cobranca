package br.com.gestorcobranca.publicapi.web.security;

import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respostas de erro RFC 9457 ({@code application/problem+json}) escritas pelos filtros da API pública, no mesmo formato
 * do {@code GlobalExceptionHandler}: {@code type}, {@code title}, {@code status}, {@code detail}, {@code code} estável e
 * {@code requestId}.
 */
final class ProblemWriter {

    private static final String TYPE_BASE = "https://gestorcobranca.com.br/problems/";

    private ProblemWriter() {
    }

    static void write(HttpServletResponse response, JsonCodec json, int status, String code, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_BASE + code.replace('.', '-'));
        body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        body.put("requestId", MDC.get(MdcKeys.REQUEST_ID));
        byte[] bytes = json.toJson(body).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
