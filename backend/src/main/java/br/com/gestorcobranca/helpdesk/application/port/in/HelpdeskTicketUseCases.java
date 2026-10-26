package br.com.gestorcobranca.helpdesk.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chamados de suporte (issues no GitHub) preparados pela IA quando ela não resolve. Nada é aberto sem a
 * confirmação explícita da pessoa, que vê antes o texto exato que vai para a issue (dados de devedor mascarados no
 * servidor). Só o próprio autor confirma, cancela ou consulta.
 */
public interface HelpdeskTicketUseCases {

    TicketView get(UUID ticketId);

    /**
     * Abre a issue. Idempotente: confirmar de novo devolve a mesma issue; texto igual já aberto pela mesma pessoa
     * nas últimas 24 h não abre outra ({@code DUPLICATE}).
     */
    TicketView confirm(UUID ticketId);

    TicketView cancel(UUID ticketId);

    /**
     * @param status     DRAFT, CREATING, OPENED, DUPLICATE, FAILED, CANCELLED ou EXPIRED
     * @param errorCode  última falha ({@code helpdesk.ticket.*}); a pessoa pode tentar de novo
     * @param expiresAt  validade do rascunho
     */
    record TicketView(UUID id, String status, String title, String body, List<String> labels, String repository,
                      Long issueNumber, String issueUrl, String errorCode, Instant createdAt, Instant expiresAt) {
    }
}
