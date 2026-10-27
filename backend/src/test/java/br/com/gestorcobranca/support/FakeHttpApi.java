package br.com.gestorcobranca.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Servidor HTTP local único da execução de testes que faz o papel das APIs de provedores externos
 * (Twilio, Plivo) — nenhuma chamada sai da máquina. Cada teste registra respostas por prefixo de
 * caminho ({@link #respond}) e lê as requisições recebidas ({@link #requests}); o endereço entra nas
 * propriedades do contexto Spring pelo {@code IntegrationTest}.
 */
public final class FakeHttpApi {

    private static volatile FakeHttpApi shared;

    private final HttpServer server;
    private final Map<String, Function<Request, Response>> handlers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private FakeHttpApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public static synchronized FakeHttpApi shared() {
        if (shared == null) {
            shared = new FakeHttpApi();
        }
        return shared;
    }

    /** Instância própria (testes de unidade dos adaptadores); pare com {@link #stop()}. */
    public static FakeHttpApi start() {
        return new FakeHttpApi();
    }

    public void stop() {
        server.stop(0);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Resposta para requisições cujo caminho começa com o prefixo (o prefixo mais longo vence). */
    public void respond(String pathPrefix, Function<Request, Response> handler) {
        handlers.put(pathPrefix, handler);
    }

    public List<Request> requests(String pathPrefix) {
        return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    public void reset(String pathPrefix) {
        handlers.keySet().removeIf(k -> k.startsWith(pathPrefix));
        requests.removeIf(r -> r.path().startsWith(pathPrefix));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        byte[] body = exchange.getRequestBody().readAllBytes();
        Request request = new Request(exchange.getRequestMethod(), path, query,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"), new String(body, StandardCharsets.UTF_8));
        requests.add(request);
        Function<Request, Response> handler = handlers.entrySet().stream()
                .filter(e -> path.startsWith(e.getKey()))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(Map.Entry::getValue).orElse(r -> new Response(404, "application/json", new byte[0]));
        Response response = handler.apply(request);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        if (response.body().length == 0) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), response.body().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.body());
            }
        }
        exchange.close();
    }

    public record Request(String method, String path, String query, String authorization, String contentType,
                          String body) {

        /** Valores do formulário com o nome (corpo {@code application/x-www-form-urlencoded}). */
        public List<String> form(String name) {
            List<String> values = new ArrayList<>();
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                    values.add(java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            return values;
        }

        public String formValue(String name) {
            List<String> values = form(name);
            return values.isEmpty() ? null : values.getFirst();
        }
    }

    public record Response(int status, String contentType, byte[] body) {

        public static Response json(int status, String json) {
            return new Response(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }

        public static Response empty(int status) {
            return new Response(status, "application/json", new byte[0]);
        }
    }
}
