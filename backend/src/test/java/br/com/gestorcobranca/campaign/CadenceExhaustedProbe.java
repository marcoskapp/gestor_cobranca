package br.com.gestorcobranca.campaign;

import br.com.gestorcobranca.shared.events.campaign.CadenceExhausted;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Consumidor de teste do contrato {@link CadenceExhausted} (o módulo legal ainda não o consome). */
@TestComponent
class CadenceExhaustedProbe {

    final List<CadenceExhausted> received = new CopyOnWriteArrayList<>();

    @EventListener
    void on(CadenceExhausted event) {
        received.add(event);
    }
}
