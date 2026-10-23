package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Versão publicada de uma política de contato (RB02): padrão da assessoria ({@code creditorId}
 * nulo) ou de um credor, que prevalece sobre a da assessoria canal a canal. Versões são
 * imutáveis; uma alteração é a publicação de nova versão com vigência a partir de
 * {@code effectiveFrom}, preservando o histórico (a vigente num instante é a de maior
 * {@code effectiveFrom} já alcançado).
 */
@Entity
@Immutable
@Table(name = "contact_policy", schema = "compliance")
public class ContactPolicy extends TenantScopedEntity {

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(name = "notes", length = 1000, updatable = false)
    private String notes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "contact_policy_rule", schema = "compliance", joinColumns = @JoinColumn(name = "policy_id"))
    private List<ChannelRule> rules = new ArrayList<>();

    protected ContactPolicy() {
    }

    public ContactPolicy(UUID creditorId, int policyVersion, Instant effectiveFrom, String notes,
                         Collection<ChannelRule> rules) {
        Guard.require(policyVersion > 0, "contact_policy.version_invalid", "Versão inválida");
        Guard.require(effectiveFrom != null, "contact_policy.effective_from_required", "Início de vigência obrigatório");
        Guard.require(rules != null && !rules.isEmpty(), "contact_policy.rules_required", "Informe a regra de ao menos um canal");
        Set<ContactChannel> channels = EnumSet.noneOf(ContactChannel.class);
        rules.forEach(r -> Guard.require(channels.add(r.getChannel()), "contact_policy.duplicate_channel",
                "Canal %s repetido na política".formatted(r.getChannel())));
        Guard.require(notes == null || notes.length() <= 1000, "contact_policy.notes_too_long", "Observações muito longas");
        this.creditorId = creditorId;
        this.policyVersion = policyVersion;
        this.effectiveFrom = effectiveFrom;
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
        this.rules = new ArrayList<>(rules);
    }

    public Optional<ChannelRule> ruleFor(ContactChannel channel) {
        return rules.stream().filter(r -> r.getChannel() == channel).findFirst();
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getNotes() {
        return notes;
    }

    public List<ChannelRule> getRules() {
        return List.copyOf(rules);
    }
}
