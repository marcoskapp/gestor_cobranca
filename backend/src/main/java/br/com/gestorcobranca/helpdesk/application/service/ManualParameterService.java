package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.port.in.ManualParameterUseCases;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Agrega os parâmetros do manual que cada módulo publica ({@link ManualParameterSource}, recebidas por injeção, sem
 * depender dos módulos). Montado uma vez na subida: os valores vêm das {@code *Properties}, fixas enquanto a
 * aplicação está no ar. Fonte com chave fora do padrão, chave repetida ou valor negativo impede a subida — um texto
 * errado no manual é pior do que um erro de configuração visível no deploy.
 */
@Service
class ManualParameterService implements ManualParameterUseCases {

    private static final Pattern MODULE = Pattern.compile("[a-z]+");
    private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]*");

    private final ManualParametersView view;

    ManualParameterService(List<ManualParameterSource> sources) {
        Map<String, BigDecimal> values = new TreeMap<>();
        for (ManualParameterSource source : sources) {
            String module = source.module();
            if (module == null || !MODULE.matcher(module).matches()) {
                throw new IllegalStateException("Parâmetros do manual: módulo inválido em " + source.getClass().getName());
            }
            source.parameters().forEach((name, value) -> {
                String key = module + "." + name;
                if (!NAME.matcher(name).matches()) {
                    throw new IllegalStateException("Parâmetros do manual: chave inválida " + key);
                }
                if (value == null || value.signum() < 0) {
                    throw new IllegalStateException("Parâmetros do manual: valor ausente ou negativo em " + key);
                }
                if (values.putIfAbsent(key, value) != null) {
                    throw new IllegalStateException("Parâmetros do manual: chave repetida " + key);
                }
            });
        }
        this.view = new ManualParametersView(values);
    }

    @Override
    public ManualParametersView current() {
        return view;
    }
}
