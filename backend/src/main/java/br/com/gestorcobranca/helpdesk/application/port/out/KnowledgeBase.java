package br.com.gestorcobranca.helpdesk.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Base de conhecimento do chat: o manual contextual do frontend, exportado em JSON versionado
 * ({@code classpath:helpdesk/knowledge-base.json}, gerado por {@code npm run knowledge}). É a única fonte do
 * texto do manual no prompt: o navegador só informa a chave da tela, nunca o conteúdo.
 */
public interface KnowledgeBase {

    /** Versão do formato aceito ({@code MANUAL_SCHEMA_VERSION} do frontend). */
    int SUPPORTED_SCHEMA_VERSION = 1;

    /** Versão do conteúdo (hash do JSON), registrada em cada resposta. */
    String contentVersion();

    Optional<Screen> screen(String id);

    Optional<Module> module(String id);

    List<Screen> screens();

    List<Term> terms(Collection<String> ids);

    /** Rótulo do perfil padrão na linguagem do usuário (ex.: {@code OPERADOR} → "Operador (negociador)"). */
    Map<String, String> profileLabels();

    /**
     * Vocabulário do produto (palavras de títulos, termos e resumos, em minúsculas): base da máscara de nomes
     * próprios no texto do chamado, para não confundir "Estação de Cobrança" com o nome de uma pessoa.
     */
    Set<String> vocabulary();

    /**
     * @param kind        OPEN (todos os autenticados), MENU (item do menu) ou CONTEXT (aberta a partir de outra tela)
     * @param permissions basta uma (vazio em OPEN; em CONTEXT, as do item do menu que contém a tela, se houver)
     */
    record Access(String kind, List<String> permissions) {

        public boolean allows(Set<String> userPermissions) {
            return "OPEN".equals(kind) || permissions.isEmpty() && "CONTEXT".equals(kind)
                    || permissions.stream().anyMatch(userPermissions::contains);
        }

        /** Aberta diretamente pela rota (sem identificador na URL). */
        public boolean direct() {
            return !"CONTEXT".equals(kind);
        }
    }

    record Screen(String id, String module, String title, String objective, List<String> purpose,
                  List<String> profiles, List<String> inputs, List<String> outputs, List<Related> related,
                  List<String> terms, String path, Access access) {

        /** Rota com o identificador no lugar de {@code :id}; nulo se a tela não tiver caminho. */
        public String route(String id) {
            if (path == null) {
                return null;
            }
            return id == null ? path : path.replace(":id", id);
        }
    }

    record Related(String screen, String why) {
    }

    record Module(String id, String title, String summary, List<String> overview, List<Connection> connections,
                  List<String> rules, List<String> terms) {
    }

    record Connection(String module, String text) {
    }

    record Term(String id, String term, String definition) {
    }
}
