package br.com.gestorcobranca.helpdesk.infrastructure.knowledge;

import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base de conhecimento lida de {@code classpath:helpdesk/knowledge-base.json} na subida. O arquivo é gerado do
 * manual do frontend ({@code npm run knowledge}; o build do frontend falha se estiver desatualizado) e versionado no
 * repositório. Formato incompatível ou arquivo ausente impedem a subida: o chat sem manual ensinaria errado.
 */
@Component
class ClasspathKnowledgeBase implements KnowledgeBase {

    static final String RESOURCE = "helpdesk/knowledge-base.json";
    private static final Logger LOG = LoggerFactory.getLogger(ClasspathKnowledgeBase.class);
    private static final Pattern WORD = Pattern.compile("\\p{L}{2,}");

    private final String contentVersion;
    private final Map<String, Screen> screens;
    private final Map<String, Module> modules;
    private final Map<String, Term> terms;
    private final Map<String, String> profiles;
    private final Set<String> vocabulary;

    @Autowired
    ClasspathKnowledgeBase(JsonCodec json) {
        this(json, read());
    }

    ClasspathKnowledgeBase(JsonCodec json, String content) {
        Export export = json.fromJson(content, Export.class);
        if (export.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("Base de conhecimento do chat com formato " + export.schemaVersion()
                    + " (esperado " + SUPPORTED_SCHEMA_VERSION + "): atualize o módulo helpdesk");
        }
        this.contentVersion = export.contentVersion();
        Map<String, Screen> s = new LinkedHashMap<>();
        for (Screen screen : export.screens()) {
            s.put(screen.id(), screen);
        }
        this.screens = Map.copyOf(s);
        Map<String, Module> m = new LinkedHashMap<>();
        export.modules().forEach(mod -> m.put(mod.id(), mod));
        this.modules = Map.copyOf(m);
        Map<String, Term> t = new LinkedHashMap<>();
        export.glossary().forEach(term -> t.put(term.id(), term));
        this.terms = Map.copyOf(t);
        this.profiles = Map.copyOf(export.profiles());
        Set<String> words = new HashSet<>();
        export.screens().forEach(sc -> words(words, sc.title()));
        export.modules().forEach(mod -> {
            words(words, mod.title());
            words(words, mod.summary());
        });
        export.glossary().forEach(term -> words(words, term.term()));
        this.profiles.values().forEach(p -> words(words, p));
        this.vocabulary = Set.copyOf(words);
        LOG.atInfo().addKeyValue("knowledgeVersion", contentVersion).addKeyValue("screens", screens.size())
                .addKeyValue("modules", modules.size()).log("Base de conhecimento do chat de suporte carregada");
    }

    private static String read() {
        try {
            return new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Base de conhecimento do chat ausente (" + RESOURCE
                    + "): gere com npm run knowledge no frontend", e);
        }
    }

    private static void words(Set<String> out, String text) {
        if (text == null) {
            return;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public String contentVersion() {
        return contentVersion;
    }

    @Override
    public Optional<Screen> screen(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(screens.get(id));
    }

    @Override
    public Optional<Module> module(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(modules.get(id));
    }

    @Override
    public List<Screen> screens() {
        return List.copyOf(screens.values());
    }

    @Override
    public List<Term> terms(Collection<String> ids) {
        return ids.stream().map(terms::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Map<String, String> profileLabels() {
        return profiles;
    }

    @Override
    public Set<String> vocabulary() {
        return vocabulary;
    }

    /** Formato do arquivo gerado por {@code frontend/scripts/export-knowledge-base.mjs}. */
    record Export(String generatedBy, String contentVersion, int schemaVersion, Map<String, String> profiles,
                  List<Module> modules, List<Screen> screens, List<Term> glossary) {
    }
}
