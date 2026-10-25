import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError } from '../../../core/http/problem';
import { Amount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { PerformanceApi } from '../data/incentive.api';
import { ACTOR_TYPE_LABELS, CHANNEL_LABELS, CREDIT_REASON_LABELS, DELAY_BUCKET_LABELS, ORIGIN_LABELS, ROLE_LABELS } from '../data/incentive.labels';
import { AttributionParticipant } from '../data/incentive.models';
import { pct } from '../data/incentive.rules';

/**
 * Atribuição de crédito de um acordo (§3 do desenho): origem, participantes e pesos de crédito
 * registrados na formalização. Aberta a partir do detalhe do acordo; insumo de contestação.
 */
@Component({
  selector: 'app-incentive-attribution-detail',
  imports: [RouterLink, Amount, AppDatePipe, EmptyState, Icon, PageHeader, Skeleton],
  template: `
    @if (data.isLoading() && !view()) {
      <div aria-busy="true" aria-label="Carregando atribuição"><app-skeleton variant="card" [rows]="5" /></div>
    } @else if (data.status() === 'error') {
      @if (notFound()) {
        <app-empty-state icon="diagram-3" title="Sem atribuição registrada para este acordo." message="A atribuição é gravada na formalização; acordos anteriores ao módulo ou ainda em processamento não têm registro.">
          <a class="btn btn-outline-primary" [routerLink]="['/negociacao/acordos', agreementId()]">Voltar ao acordo</a>
        </app-empty-state>
      } @else {
        <app-empty-state icon="diagram-3" tone="error" title="Não foi possível carregar a atribuição." message="O credor do acordo pode estar fora do seu escopo.">
          <button class="btn btn-outline-primary" type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      }
    } @else if (view(); as a) {
      <app-page-header title="Atribuição de crédito do acordo" [subtitle]="origins[a.origin] + ' · formalizado em ' + (a.formalizedAt | appDate)">
        @if (canOpenAgreement()) {
          <a class="btn btn-link" [routerLink]="['/negociacao/acordos', a.agreementId]">
            <app-icon name="arrow-left" />
            Acordo
          </a>
        }
      </app-page-header>
      <div class="in-grid">
        <div class="in-main">
          <section class="app-card" aria-labelledby="at-credits">
            <h2 class="app-card__title" id="at-credits">Créditos</h2>
            @if (a.credits.length === 0) {
              <p class="app-muted mb-0">Nenhum crédito individual (por exemplo, acordo fechado sozinho pelo assistente sem equipe dona da fila).</p>
            } @else {
              <div class="in-scroll">
                <table class="in-table">
                  <caption class="visually-hidden">Créditos do acordo</caption>
                  <thead>
                    <tr>
                      <th scope="col">Beneficiário</th>
                      <th scope="col">Papel</th>
                      <th scope="col" class="in-end">Peso</th>
                      <th scope="col" class="in-end">Renegociação</th>
                      <th scope="col">Motivo</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (c of a.credits; track $index) {
                      <tr>
                        <th scope="row">{{ c.beneficiaryType === 'TEAM' ? 'Equipe ' + teams.name(c.beneficiaryId) : users.name(c.beneficiaryId) }}</th>
                        <td>{{ c.role ? (roles[c.role] ?? c.role) : 'Pool' }}</td>
                        <td class="in-end">{{ pct(c.weight) }}</td>
                        <td class="in-end">{{ pct(c.renegotiationFactor) }}</td>
                        <td>{{ c.reason ? (reasons[c.reason] ?? c.reason) : '—' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
            <p class="app-muted in-small mt-2 mb-0">Os pesos ficam fixados na formalização; parcelas pagas depois são creditadas por eles.</p>
          </section>
          <section class="app-card" aria-labelledby="at-participants">
            <h2 class="app-card__title" id="at-participants">Participantes</h2>
            <ul class="in-list">
              @for (p of a.participants; track $index) {
                <li class="in-item d-flex justify-content-between flex-wrap gap-2">
                  <span><strong>{{ roles[p.role] ?? p.role }}</strong> · {{ actor(p) }}</span>
                  <span class="app-muted">{{ actorTypes[p.actorType] ?? p.actorType }}</span>
                </li>
              } @empty {
                <li class="app-muted">Nenhum participante registrado.</li>
              }
            </ul>
          </section>
        </div>
        <aside class="in-side">
          <section class="app-card" aria-labelledby="at-facts">
            <h2 class="app-card__title" id="at-facts">Dados do acordo</h2>
            <dl class="app-definition-list">
              <dt>Credor</dt>
              <dd>{{ creditors.name(a.creditorId) }}</dd>
              <dt>Valor do acordo</dt>
              <dd><app-amount [value]="a.agreementAmount" /></dd>
              <dt>Valor incremental</dt>
              <dd><app-amount [value]="a.incrementalAmount" /></dd>
              <dt>Canal</dt>
              <dd>{{ a.channel ? (channels[a.channel] ?? a.channel) : '—' }}</dd>
              <dt>Faixa de atraso</dt>
              <dd>{{ a.delayBucket ? buckets[a.delayBucket] : '—' }}</dd>
              <dt>Nível da escada</dt>
              <dd>{{ a.concessionLevel ?? '—' }}</dd>
              <dt>Uso do envelope</dt>
              <dd>{{ pct(a.envelopeUsage) }}</dd>
              @if (a.teamId) {
                <dt>Equipe da fila</dt>
                <dd>{{ teams.name(a.teamId) }}</dd>
              }
              @if (a.replacedAgreementId) {
                <dt>Renegociação de</dt>
                <dd>
                  @if (canOpenAgreement()) {
                    <a [routerLink]="['/negociacao/acordos', a.replacedAgreementId]">acordo anterior</a>
                  } @else {
                    acordo anterior
                  }
                </dd>
              }
            </dl>
          </section>
        </aside>
      </div>
    }
  `,
  styleUrl: '../incentive.scss',
})
export class AttributionDetail {
  readonly agreementId = input.required<string>();
  private readonly api = inject(PerformanceApi);
  private readonly auth = inject(AuthService);
  protected readonly origins = ORIGIN_LABELS as Record<string, string>;
  protected readonly roles = ROLE_LABELS;
  protected readonly actorTypes = ACTOR_TYPE_LABELS;
  protected readonly reasons = CREDIT_REASON_LABELS;
  protected readonly channels = CHANNEL_LABELS;
  protected readonly buckets = DELAY_BUCKET_LABELS as Record<string, string>;
  protected readonly pct = pct;
  protected readonly canOpenAgreement = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  protected readonly data = rxResource({ params: () => this.agreementId(), stream: ({ params }) => this.api.attribution(params) });
  protected readonly view = valueOr(this.data, undefined);
  protected readonly notFound = computed(() => this.data.status() === 'error' && toApiError(this.data.error()).status === 404);
  protected readonly users = nameMap(
    inject(UserNames),
    () => {
      const a = this.view();
      return [...(a?.credits ?? []).filter((c) => c.beneficiaryType === 'USER').map((c) => c.beneficiaryId), ...(a?.participants ?? []).filter((p) => p.actorType === 'USER').map((p) => p.actorId)];
    },
    { batch: USER_NAMES_BATCH },
  );
  protected readonly teams = nameMap(inject(TeamLookup), () => {
    const a = this.view();
    return [a?.teamId, ...(a?.credits ?? []).filter((c) => c.beneficiaryType === 'TEAM').map((c) => c.beneficiaryId)];
  });
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.view()?.creditorId]);

  protected actor(p: AttributionParticipant): string {
    if (p.actorType === 'USER' && p.actorId) {
      return this.users.name(p.actorId);
    }
    return ACTOR_TYPE_LABELS[p.actorType] ?? p.actorType;
  }
}
