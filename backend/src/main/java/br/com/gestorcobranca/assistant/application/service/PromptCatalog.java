package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.PromptSource;
import br.com.gestorcobranca.assistant.application.port.out.PromptSource.PromptTemplate;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Prompts versionados disponíveis (recursos do módulo carregados uma vez). */
@Component
class PromptCatalog {

    private final List<PromptTemplate> prompts;

    PromptCatalog(PromptSource source) {
        this.prompts = source.all().stream()
                .sorted(Comparator.comparing(PromptTemplate::code).thenComparingInt(PromptTemplate::version))
                .toList();
    }

    List<PromptTemplate> all() {
        return prompts;
    }

    Optional<PromptTemplate> find(String code, int version) {
        return prompts.stream().filter(p -> p.code().equals(code) && p.version() == version).findFirst();
    }

    PromptTemplate require(String code, int version) {
        return find(code, version).orElseThrow(() -> new BusinessRuleException("assistant_policy.prompt_not_found",
                "Prompt inexistente: %s v%d".formatted(code, version)));
    }
}
