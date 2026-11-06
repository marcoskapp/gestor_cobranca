package br.com.gestorcobranca.helpdesk.infrastructure.knowledge;

import br.com.gestorcobranca.helpdesk.application.port.in.ManualParameterUseCases;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.helpdesk.domain.service.ManualText;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
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
 * <p>
 * Os marcadores de parâmetro do manual ({@code {{debt.cumulativeWindow}}}, ux.md §8.7) são resolvidos aqui, uma vez,
 * com os valores que a aplicação aplica ({@link ManualParameterUseCases}): o chat cita o mesmo número que a tela.
 * Marcador desconhecido ou parâmetro sem valor também impedem a subida.
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
    private final Map<String, Indicator> indicators;
    private final Map<String, String> profiles;
    private final Set<String> vocabulary;

    @Autowired
    ClasspathKnowledgeBase(JsonCodec json, ManualParameterUseCases parameters) {
        this(json, readExport(), parameters.current().values());
    }

    ClasspathKnowledgeBase(JsonCodec json, String content, Map<String, BigDecimal> parameterValues) {
        Export raw = json.fromJson(content, Export.class);
        if (raw.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("Base de conhecimento do chat com formato " + raw.schemaVersion()
                    + " (esperado " + SUPPORTED_SCHEMA_VERSION + "): atualize o módulo helpdesk");
        }
        ManualText text = new ManualText(raw.phrases() == null ? Map.of() : raw.phrases(),
                raw.parameters() == null ? Map.of() : raw.parameters(), parameterValues);
        Export export = resolved(raw, text);
        // versão efetiva = versão do JSON + valores usados nas frases: o mesmo manual com outra configuração é outro
        // texto, e a versão vai com cada resposta (helpdesk.chat_message.knowledge_version, varchar(32))
        this.contentVersion = export.contentVersion() + "." + text.valuesFingerprint();
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
        Map<String, Indicator> ind = new LinkedHashMap<>();
        (export.indicators() == null ? List.<Indicator>of() : export.indicators()).forEach(i -> ind.put(i.id(), i));
        this.indicators = Collections.unmodifiableMap(ind);
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
                .addKeyValue("modules", modules.size()).addKeyValue("indicators", indicators.size()).log("Base de conhecimento do chat de suporte carregada");
    }

    /** Mesma exportação com os marcadores de parâmetro trocados pelos valores da aplicação em todo texto exibível. */
    private static Export resolved(Export e, ManualText text) {
        List<Module> modules = e.modules().stream().map(m -> new Module(m.id(), text.resolve(m.title()),
                text.resolve(m.summary()), all(text, m.overview()),
                m.connections() == null ? null : m.connections().stream()
                        .map(c -> new Connection(c.module(), text.resolve(c.text()))).toList(),
                all(text, m.rules()), m.terms())).toList();
        List<Screen> screens = e.screens().stream().map(s -> new Screen(s.id(), s.module(), text.resolve(s.title()),
                text.resolve(s.objective()), all(text, s.purpose()), s.profiles(), all(text, s.inputs()),
                all(text, s.outputs()), s.related() == null ? null : s.related().stream()
                        .map(r -> new Related(r.screen(), text.resolve(r.why()))).toList(),
                s.terms(), s.path(), s.access())).toList();
        List<Term> glossary = e.glossary().stream()
                .map(t -> new Term(t.id(), text.resolve(t.term()), text.resolve(t.definition()))).toList();
        List<Indicator> indicators = e.indicators() == null ? null : e.indicators().stream()
                .map(i -> new Indicator(i.id(), i.module(), text.resolve(i.title()), text.resolve(i.what()),
                        text.resolve(i.how()), text.resolve(i.use()), i.screens())).toList();
        return new Export(e.generatedBy(), e.contentVersion(), e.schemaVersion(), e.profiles(), modules, screens,
                glossary, indicators, e.parameters(), e.phrases());
    }

    private static List<String> all(ManualText text, List<String> values) {
        return values == null ? null : values.stream().map(text::resolve).toList();
    }

    static String readExport() {
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
    public Optional<Indicator> indicator(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(indicators.get(id));
    }

    @Override
    public List<Indicator> screenIndicators(String screenId) {
        return screenId == null ? List.of()
                : indicators.values().stream().filter(i -> i.screens() != null && i.screens().contains(screenId)).toList();
    }

    @Override
    public List<Indicator> moduleIndicators(String moduleId) {
        return moduleId == null ? List.of() : indicators.values().stream().filter(i -> moduleId.equals(i.module())).toList();
    }

    @Override
    public Map<String, String> profileLabels() {
        return profiles;
    }

    @Override
    public Set<String> vocabulary() {
        return vocabulary;
    }

    /**
     * Formato do arquivo gerado por {@code frontend/scripts/export-knowledge-base.mjs}; {@code parameters} (formato de
     * cada parâmetro) e {@code phrases} (frases dos marcadores) desde o formato 2.
     */
    record Export(String generatedBy, String contentVersion, int schemaVersion, Map<String, String> profiles,
                  List<Module> modules, List<Screen> screens, List<Term> glossary, List<Indicator> indicators,
                  Map<String, String> parameters, Map<String, ManualText.Phrase> phrases) {
    }
}
