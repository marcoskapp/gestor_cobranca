package br.com.gestorcobranca.legal.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Escritórios parceiros e seus advogados (usuários da assessoria, tipicamente com o perfil
 * ADVOGADO_PARCEIRO). Gestão exige {@code LEGAL_MANAGE}, acesso a todos os credores e usuário
 * interno; a atribuição de casos a escritórios depende do parâmetro "quem conduz"
 * ({@code conductModel}). Um usuário pertence a no máximo um escritório por vez.
 */
public interface LawFirmUseCases {

    List<LawFirmView> list();

    LawFirmView get(UUID lawFirmId);

    LawFirmView create(LawFirmCommand command);

    LawFirmView update(UUID lawFirmId, LawFirmCommand command);

    LawFirmView setActive(UUID lawFirmId, boolean active);

    List<MemberView> members(UUID lawFirmId);

    MemberView addMember(UUID lawFirmId, UUID userId);

    MemberView removeMember(UUID lawFirmId, UUID userId);

    /** @param cnpj ignorado na alteração (imutável) */
    record LawFirmCommand(String name, String cnpj, String oabRegistration, String contactEmail) {
    }

    record LawFirmView(UUID id, String name, String maskedCnpj, String cnpj, String oabRegistration,
                       String contactEmail, boolean active, long version) {
    }

    record MemberView(UUID lawFirmId, UUID userId, String userName, boolean active, Instant joinedAt, Instant leftAt) {
    }
}
