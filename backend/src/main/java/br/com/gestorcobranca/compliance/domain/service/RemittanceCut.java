package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;

import java.util.ArrayList;
import java.util.List;

/**
 * Corte da remessa ao credor pelo teto de títulos (decisão 1486): na ordem recebida, inclui avisos inteiros até o
 * próximo passar do teto (o texto cita todos os títulos de cada aviso). Sempre ao menos um aviso, mesmo que sozinho
 * passe do teto: senão o credor nunca seria avisado.
 */
public final class RemittanceCut {

    private RemittanceCut() {
    }

    public static List<CreditorErasureNotice> take(List<CreditorErasureNotice> ordered, int maxTitles) {
        List<CreditorErasureNotice> chosen = new ArrayList<>();
        int titles = 0;
        for (CreditorErasureNotice n : ordered) {
            int size = n.getItems().size();
            if (!chosen.isEmpty() && titles + size > maxTitles) {
                break;
            }
            chosen.add(n);
            titles += size;
        }
        return chosen;
    }
}
