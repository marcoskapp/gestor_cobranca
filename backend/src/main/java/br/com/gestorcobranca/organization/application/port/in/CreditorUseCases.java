package br.com.gestorcobranca.organization.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Cadastro de credores (F03). Todas as operações respeitam o escopo de credores do ator. */
public interface CreditorUseCases {

    CreditorView create(CreditorCommand command);

    CreditorView update(UUID creditorId, CreditorCommand command);

    CreditorView changeStatus(UUID creditorId, boolean active);

    CreditorView get(UUID creditorId);

    /** Para outros módulos: credor existente, visível e ativo; senão lança exceção de negócio. */
    CreditorView requireActive(UUID creditorId);

    List<CreditorView> getAll(Collection<UUID> creditorIds);

    PageResponse<CreditorView> search(String text, String status, PageQuery page);

    record CreditorCommand(String legalName, String tradeName, String document, String externalCode, String contactEmail) {
    }

    record CreditorView(UUID id, String legalName, String tradeName, String document, String externalCode,
                        String contactEmail, String status) {
    }
}
