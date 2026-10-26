package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase.Screen;
import br.com.gestorcobranca.shared.ai.LanguageModel.ToolDefinition;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static br.com.gestorcobranca.helpdesk.application.service.HelpdeskDataTools.map;

/**
 * Contexto de cada chamada ao modelo (JSON nas instruções de sistema, nunca nas mensagens): quem pergunta (perfis,
 * permissões em linguagem do usuário, se vê todos os credores), a tela atual com o manual dela e do módulo, as telas
 * que a pessoa pode abrir, as referências já citadas na conversa e as ferramentas disponíveis. Nada de dado de
 * devedor; o nome da pessoa não vai (só o primeiro nome, para cumprimentar).
 */
@Component
class ChatContextBuilder {

    /** Versão do formato do contexto (registrada com a resposta, junto do prompt e da base de conhecimento). */
    static final int CONTEXT_SCHEMA_VERSION = 1;

    private final KnowledgeBase knowledge;
    private final Clock clock;

    ChatContextBuilder(KnowledgeBase knowledge, Clock clock) {
        this.knowledge = knowledge;
        this.clock = clock;
    }

    Map<String, Object> build(TurnState state, List<ToolDefinition> tools) {
        TurnState.UserProfile p = state.profile;
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("versaoContexto", CONTEXT_SCHEMA_VERSION);
        ctx.put("sistema", "Gestor de Cobrança");
        ctx.put("hoje", LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).toString());
        ctx.put("pessoa", map("primeiroNome", firstName(p.name()), "perfis", p.roleLabels(),
                "todosOsCredores", p.allCreditors(), "permissoes", permissionLabels(p.permissions())));
        if (state.screen != null) {
            ctx.put("telaAtual", screenManual(state.screen, knowledge));
            knowledge.module(state.screen.module()).ifPresent(m -> ctx.put("moduloAtual", map("modulo", m.id(),
                    "titulo", m.title(), "resumo", m.summary(), "regras", m.rules())));
        } else {
            ctx.put("telaAtual", "não informada");
        }
        List<Map<String, Object>> screens = new ArrayList<>();
        for (Screen s : knowledge.screens()) {
            if (s.access().direct() && s.path() != null && s.access().allows(p.permissions())) {
                screens.add(map("tela", s.id(), "titulo", s.title()));
            }
        }
        ctx.put("telasQueAPessoaPodeAbrir", screens);
        ctx.put("referenciasDaConversa", state.references.toMap().keySet().stream().sorted().toList());
        ctx.put("ferramentasDisponiveis", tools.stream().map(ToolDefinition::name).toList());
        ctx.put("chamados", tools.stream().anyMatch(t -> HelpdeskToolbox.OPEN_TICKET.equals(t.name()))
                ? "disponível (com confirmação da pessoa; retorno por e-mail)" : "indisponível neste ambiente");
        return ctx;
    }

    /** Manual de uma tela para o modelo (também usado por {@code consultar_manual}). */
    static Map<String, Object> screenManual(Screen s, KnowledgeBase knowledge) {
        Map<String, String> profiles = knowledge.profileLabels();
        return map("tela", s.id(), "titulo", s.title(), "objetivo", s.objective(), "paraQueServe", s.purpose(),
                "quemUsa", s.profiles().stream().map(code -> profiles.getOrDefault(code, code)).toList(),
                "deOndeVemOsDados", s.inputs(), "paraOndeVaiOResultado", s.outputs(),
                "telasRelacionadas", s.related().stream().map(r -> map("tela", r.screen(), "porque", r.why(), "titulo",
                        knowledge.screen(r.screen()).map(Screen::title).orElse(null))).toList(),
                "termos", knowledge.terms(s.terms()).stream().map(t -> map("termo", t.term(), "definicao", t.definition()))
                        .toList(),
                "abertura", s.access().direct() ? "pelo menu ou pela rota" : "a partir de outra tela, escolhendo o registro");
    }

    private static List<String> permissionLabels(Set<String> codes) {
        List<String> out = new ArrayList<>();
        for (Permission p : Permission.values()) {
            if (codes.contains(p.name())) {
                out.add(p.description());
            }
        }
        return out;
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().split("\\s+")[0];
    }
}
