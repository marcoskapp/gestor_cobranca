package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.shared.logging.MdcKeys;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Erros de requisição da API pública (corpo ilegível, validação, parâmetro ausente, tipo de mídia) no contrato
 * RFC 9457 com {@code code} estável e {@code requestId}. Restrito aos controllers v1 e com precedência sobre o
 * tratador padrão do Spring Boot; exceções de domínio continuam no {@code GlobalExceptionHandler} (mesmo formato).
 */
@RestControllerAdvice(basePackageClasses = PublicApiExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PublicApiExceptionHandler {

    private static final URI TYPE_BASE = URI.create("https://gestorcobranca.com.br/problems/");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation.failed", "Dados inválidos");
        p.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage()))).toList());
        return p;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidParameters(HandlerMethodValidationException e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation.failed", "Dados inválidos");
        List<Map<String, String>> errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(err -> Map.of(
                        "field", String.valueOf(r.getMethodParameter().getParameterName()),
                        "message", String.valueOf(err.getDefaultMessage()))))
                .toList();
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail malformed(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "request.malformed", "Requisição malformada");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MissingRequestHeaderException.class})
    ProblemDetail missing(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "request.missing_parameter", "Parâmetro obrigatório ausente");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail mediaType(HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "request.media_type", "Tipo de conteúdo não suportado");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "request.method_not_allowed", "Método não suportado");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "upload.too_large", "Arquivo excede o tamanho máximo permitido");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(TYPE_BASE.resolve(code.replace('.', '-')));
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        p.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        return p;
    }
}
