package br.com.gestorcobranca.shared.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fonte de pessoas naturais ligadas a um devedor <strong>sem serem devedoras</strong> — hoje, os representantes da
 * empresa devedora (cobrança B2B, {@code docs/tecnico/desenho/cobranca-b2b.md} §5.1) — localizadas pelo CPF para a
 * solicitação do titular (LGPD art. 18): o representante é titular dos próprios dados (nome, CPF, papel) mesmo não
 * estando em {@code debt.debtor}. Linguagem publicada do kernel, como {@link DataSharingSource}: o módulo dono
 * implementa e o compliance recebe por injeção, sem depender dele.
 * <p>
 * Regras para quem implementa: localizar só pelo blind index do documento (nunca por nome); devolver ids, papel e
 * período — nunca o documento ou o nome; respeitar o escopo por credor da consulta (nulo = todos); a RLS do tenant vem
 * da transação de quem chama.
 */
public interface RelatedPersonSource {

    /** Identificador estável da fonte (o módulo dono), ex.: {@code debt}. */
    String id();

    /**
     * Vínculos do CPF informado (com ou sem máscara). CPF inválido → lista vazia.
     *
     * @param creditorScope credores do escopo de quem consulta; nulo = todos
     */
    List<RelatedPersonLink> linksOf(String cpf, java.util.Set<UUID> creditorScope);

    /**
     * Um vínculo da pessoa com um devedor.
     *
     * @param relation     código estável do vínculo (ex.: {@code COMPANY_REPRESENTATIVE})
     * @param debtorId     devedor ao qual a pessoa está ligada (a empresa)
     * @param role         papel no vínculo (ex.: {@code MANAGING_PARTNER})
     * @param dataCategory categorias de dados tratadas, em linguagem simples ("nome, CPF e papel")
     * @param since        início do registro
     * @param endedAt      encerramento (nulo = vigente)
     * @param link         tela que abre o registro (nulo se não houver)
     */
    record RelatedPersonLink(String relation, UUID debtorId, String role, String dataCategory, Instant since,
                             Instant endedAt, DataSharingLink link) {

        public RelatedPersonLink {
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(debtorId, "debtorId");
            Objects.requireNonNull(dataCategory, "dataCategory");
        }
    }
}
