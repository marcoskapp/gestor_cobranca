package br.com.gestorcobranca.helpdesk.application.port.in;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Parâmetros da aplicação citados no manual, nos tours, nas novidades e em textos de tela (prazos, limites e
 * quantidades), com os valores que os módulos de fato aplicam — para o texto acompanhar uma mudança de configuração
 * (docs/tecnico/desenho/ux.md §8.7). Só números de configuração, iguais para todas as assessorias: a leitura é
 * liberada a todo usuário interno, como o próprio manual.
 */
public interface ManualParameterUseCases {

    /** Valores atuais (fixos enquanto a aplicação está no ar). */
    ManualParametersView current();

    /** @param values valor de cada parâmetro pela chave {@code <modulo>.<nome>} (ex.: {@code debt.cumulativeWindowDays}) */
    record ManualParametersView(Map<String, BigDecimal> values) {

        public ManualParametersView {
            values = Map.copyOf(values);
        }
    }
}
