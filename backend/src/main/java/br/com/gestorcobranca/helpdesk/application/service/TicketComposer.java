package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.helpdesk.domain.service.TicketRedactor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;

/**
 * Monta o texto do chamado: o título e o resumo escritos pela IA passam pela máscara determinística
 * ({@link TicketRedactor}) e o servidor acrescenta o contexto — tela de origem, assessoria, e-mail de quem abriu
 * (decisão do responsável) e o aviso de que o retorno será dado por e-mail. A conversa em si nunca é publicada.
 */
@Component
class TicketComposer {

    static final String RETURN_NOTICE = "O retorno será dado por e-mail";
    static final int MAX_TITLE = 120;
    static final int MAX_SUMMARY = 4000;

    private final KnowledgeBase knowledge;
    private final TicketRedactor redactor;

    TicketComposer(KnowledgeBase knowledge) {
        this.knowledge = knowledge;
        this.redactor = new TicketRedactor(knowledge.vocabulary());
    }

    record Composed(String title, String body, String contentSha256) {
    }

    Composed compose(String rawTitle, String rawSummary, TurnState state, Collection<String> knownNames, Instant now) {
        String title = truncate(oneLine(redactor.redact(rawTitle, knownNames)), MAX_TITLE);
        String summary = truncate(redactor.redact(rawSummary, knownNames).strip(), MAX_SUMMARY);
        String screen = state.screen == null ? "não informada"
                : state.screen.title() + " (`" + state.screen.id() + "`)";
        String email = state.profile.email();
        String body = """
                ## Resumo

                %s

                ## Contexto

                - Tela de origem: %s
                - Assessoria: %s
                - Aberto por: %s
                - Perfis: %s
                - Aberto em: %s (horário de Brasília)
                - Base de conhecimento: %s

                > %s para **%s**.

                <sub>Chamado preparado pelo chat de suporte do Gestor de Cobrança e confirmado por quem abriu. Dados de \
                devedores são mascarados automaticamente e a conversa não é publicada.</sub>
                """.formatted(summary, screen, nonBlank(state.profile.tenantName()), email,
                String.join(", ", state.profile.roleLabels()), Formats.dateTime(now), knowledge.contentVersion(),
                RETURN_NOTICE, email);
        return new Composed("[Suporte] " + title, body.strip() + "\n", sha256(title + "\n" + summary));
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }

    private static String nonBlank(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
