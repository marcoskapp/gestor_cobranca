package br.com.gestorcobranca.payment.infrastructure.provider.pix;

import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixConnection;
import org.springframework.web.client.RestClient;

/** Cliente HTTP com TLS mútuo para a conta (certificado de cliente do PSP). */
interface PixHttpClientFactory {

    RestClient client(PixConnection connection);
}
