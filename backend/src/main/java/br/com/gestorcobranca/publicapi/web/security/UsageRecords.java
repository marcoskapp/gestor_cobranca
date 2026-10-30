package br.com.gestorcobranca.publicapi.web.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Registros processados pela requisição (insumo do faturamento por registro), informados pelos endpoints e lidos pelo
 * filtro de medição ao fim da requisição. Registros processados de forma assíncrona (importação) são atribuídos depois,
 * pelo evento de fim do lote.
 */
public final class UsageRecords {

    static final String ATTRIBUTE = UsageRecords.class.getName() + ".records";

    private UsageRecords() {
    }

    public static void add(HttpServletRequest request, long records) {
        Object current = request.getAttribute(ATTRIBUTE);
        long total = (current instanceof Long l ? l : 0L) + Math.max(0, records);
        request.setAttribute(ATTRIBUTE, total);
    }

    static long of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof Long l ? l : 0L;
    }
}
