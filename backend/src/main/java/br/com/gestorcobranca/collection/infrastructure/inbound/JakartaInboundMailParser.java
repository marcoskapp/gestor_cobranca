package br.com.gestorcobranca.collection.infrastructure.inbound;

import br.com.gestorcobranca.collection.application.port.out.InboundMailParser;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Leitura MIME com Jakarta Mail (sem rede: sessão vazia). Texto: a primeira parte {@code text/plain}; sem ela, o HTML
 * convertido em texto com a lista de permissões vazia do jsoup (scripts, estilos, imagens remotas e links são
 * descartados — nada é renderizado nem buscado). Anexos: partes com nome de arquivo ou disposição {@code attachment},
 * em profundidade limitada (e-mail encaminhado dentro de e-mail conta como um anexo {@code message/rfc822}).
 */
@Component
class JakartaInboundMailParser implements InboundMailParser {

    private static final Logger LOG = LoggerFactory.getLogger(JakartaInboundMailParser.class);
    private static final int MAX_DEPTH = 8;
    private static final Session SESSION = Session.getInstance(new Properties());

    @Override
    public ParsedMail parse(byte[] raw, int maxTextChars, int maxAttachments) {
        try {
            MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(raw));
            Collector collector = new Collector(maxAttachments);
            walk(message, collector, 0);
            String text = collector.plain != null ? collector.plain
                    : collector.html != null ? htmlToText(collector.html) : "";
            text = normalize(text);
            boolean truncated = text.length() > maxTextChars;
            if (truncated) {
                text = text.substring(0, maxTextChars);
            }
            return new ParsedMail(false, from(message), header(message, "Message-ID"), header(message, "In-Reply-To"),
                    recipients(message), subject(message), text, truncated, collector.attachments, collector.count);
        } catch (MessagingException | IOException | RuntimeException e) {
            LOG.atWarn().addKeyValue("error", e.getClass().getSimpleName()).log("E-mail recebido ilegível (MIME inválido)");
            return ParsedMail.invalid();
        }
    }

    private void walk(Part part, Collector collector, int depth) throws MessagingException, IOException {
        if (depth > MAX_DEPTH) {
            throw new MessagingException("MIME aninhado demais");
        }
        String disposition = part.getDisposition();
        String fileName = fileName(part);
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || fileName != null;
        if (attachment && !part.isMimeType("multipart/*")) {
            collector.attachment(fileName, baseType(part.getContentType()), part);
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                walk(child, collector, depth + 1);
            }
        } else if (part.isMimeType("text/plain")) {
            if (collector.plain == null) {
                collector.plain = String.valueOf(part.getContent());
            }
        } else if (part.isMimeType("text/html")) {
            if (collector.html == null) {
                collector.html = String.valueOf(part.getContent());
            }
        } else if (part.isMimeType("message/rfc822")) {
            collector.attachment("mensagem-encaminhada.eml", "message/rfc822", part);
        } else {
            collector.attachment(fileName, baseType(part.getContentType()), part);
        }
    }

    /** HTML → texto: só o texto dos nós; quebras de bloco preservadas como linhas. */
    static String htmlToText(String html) {
        Document dirty = Jsoup.parse(html);
        dirty.outputSettings().prettyPrint(false);
        dirty.select("br").after("\\n");
        dirty.select("p, div, li, tr, h1, h2, h3, h4, h5, h6, blockquote").prepend("\\n");
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        String clean = Jsoup.clean(dirty.html(), "", Safelist.none(), settings);
        return org.jsoup.parser.Parser.unescapeEntities(clean.replace("\\n", "\n"), false);
    }

    private static String normalize(String text) {
        String clean = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "");
        String lines = java.util.Arrays.stream(clean.split("\n", -1)).map(String::strip)
                .collect(java.util.stream.Collectors.joining("\n"));
        return lines.replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String from(MimeMessage message) throws MessagingException {
        Address[] from;
        try {
            from = message.getFrom();
        } catch (AddressException e) {
            return null;
        }
        if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress address)) {
            return null;
        }
        String email = address.getAddress();
        return email == null || !email.contains("@") ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> recipients(MimeMessage message) throws MessagingException {
        Set<String> out = new LinkedHashSet<>();
        for (String name : List.of("To", "Cc", "Delivered-To", "X-Original-To")) {
            String[] values = message.getHeader(name);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                try {
                    for (InternetAddress a : InternetAddress.parseHeader(value, false)) {
                        if (a.getAddress() != null) {
                            out.add(a.getAddress().trim().toLowerCase(Locale.ROOT));
                        }
                    }
                } catch (AddressException e) {
                    // endereço inválido no cabeçalho: ignorado (o envelope do provedor é o que vale)
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String subject(MimeMessage message) throws MessagingException {
        String subject = message.getSubject();
        return subject == null ? null : normalize(subject);
    }

    private static String header(MimeMessage message, String name) throws MessagingException {
        String value = message.getHeader(name, null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String fileName(Part part) throws MessagingException {
        String name = part.getFileName();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            name = MimeUtility.decodeText(name);
        } catch (UnsupportedEncodingException e) {
            // mantém o nome como veio
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return name.isEmpty() ? null : name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String baseType(String contentType) {
        return contentType == null ? "application/octet-stream"
                : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    }

    private static final class Collector {

        private final int maxAttachments;
        private final List<Attachment> attachments = new ArrayList<>();
        private int count;
        private String plain;
        private String html;

        Collector(int maxAttachments) {
            this.maxAttachments = maxAttachments;
        }

        void attachment(String fileName, String declaredType, Part part) throws MessagingException, IOException {
            count++;
            if (attachments.size() > maxAttachments) {
                return; // excesso: só conta (o e-mail vai para a quarentena; o original guarda tudo)
            }
            try (InputStream in = part.getInputStream()) {
                attachments.add(new Attachment(fileName == null ? "anexo-" + count : fileName, declaredType,
                        in.readAllBytes()));
            }
        }
    }
}
