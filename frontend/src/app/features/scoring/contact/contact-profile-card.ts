import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { catchError, of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ContactIntelligenceApi } from '../data/scoring.api';
import { SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { ContactProfileView, KnownContact } from '../data/contact-intelligence.models';
import { CONFIDENCE_HINTS, CONFIDENCE_LABELS, CONTACT_CHANNEL_LABELS, contactRows, ratePercent } from '../data/contact-intelligence.rules';
import { integer } from '../data/scoring.rules';

let nextId = 0;

/**
 * Cartão "Melhor forma de contato" (inteligência de contato, fase 1) no painel do caso e na ficha do
 * devedor: canal preferido e os contatos ordenados pela taxa ajustada (ordem do servidor), com a
 * explicação, a confiança e o melhor horário. O valor exibido é o mascarado que a tela já tem (junção
 * pelo id do contato; nada novo é pedido ao servidor). Só com `SCORING_VIEW` (sem ela não consulta nem
 * renderiza). Fora do escopo (404) o cartão não aparece. Orienta o contato; não decide nem bloqueia.
 */
@Component({
  selector: 'app-contact-profile-card',
  imports: [Icon, InfoHint, AppDatePipe, NgbTooltip],
  template: `
    @if (canView() && !hidden()) {
      <section class="cp" [attr.aria-labelledby]="id + '-t'" data-tour="contact-profile" [attr.aria-busy]="resource.isLoading()">
        <h3 class="cp__title" [id]="id + '-t'">
          Melhor forma de contato
          <app-info-hint indicator="scoring.contact.preferred" label="Melhor forma de contato" />
        </h3>

        @if (resource.status() === 'error') {
          <p class="cp__error" role="alert">
            <app-icon name="exclamation-circle" />{{ errorText() }}
            <button type="button" class="btn btn-link btn-sm p-0 align-baseline" (click)="resource.reload()">Tentar novamente</button>
          </p>
        } @else if (!profile()) {
          <p class="cp__muted" role="status">Carregando o perfil de contato…</p>
        } @else if (!profile()!.sufficient) {
          <p class="cp__muted" role="status">{{ profile()!.emptyReason || 'Ainda não há tentativas suficientes para montar o perfil de contato.' }}</p>
        } @else {
          @let p = profile()!;
          <p class="cp__preferred">
            @if (p.preferredChannel) {
              <strong>{{ channels[p.preferredChannel] }}</strong>
              <span class="visually-hidden">: </span>
            }
            <span>{{ p.preferredChannelExplanation }}</span>
          </p>
          @if (rows().length) {
            <p class="cp__small">
              Contatos pela taxa ajustada, com a confiança de cada um
              <app-info-hint indicator="scoring.contact.confidence" label="Confiança do perfil de contato" />
            </p>
            <ul class="cp__list" aria-label="Contatos do devedor pela taxa ajustada">
              @for (r of rows(); track r.key) {
                <li class="cp__item">
                  <div class="cp__head">
                    <span class="cp__value app-numeric" [class.cp__value--unknown]="!r.known">{{ r.value }}</span>
                    <span class="cp__kind">{{ r.kind ? r.kind + ' · ' : '' }}{{ channels[r.profile.channel] }}</span>
                    <span class="cp__rate">
                      @if (r.insufficient) {
                        <span class="app-status app-status--neutral">Dados insuficientes</span>
                      } @else {
                        <span class="app-numeric"><strong>{{ rate(r.profile.rate) }}</strong> <span class="cp__muted-inline">taxa ajustada</span></span>
                      }
                      <span class="app-status app-status--neutral cp__conf" tabindex="0" [ngbTooltip]="confidenceHints[r.profile.confidence]">
                        Confiança {{ confidence[r.profile.confidence].toLowerCase() }}
                        <span class="visually-hidden">. {{ confidenceHints[r.profile.confidence] }}</span>
                      </span>
                    </span>
                  </div>
                  <p class="cp__text">{{ r.profile.explanation }}</p>
                  @if (r.profile.bestSlotExplanation) {
                    <p class="cp__text"><app-icon name="clock" />{{ r.profile.bestSlotExplanation }}</p>
                  }
                  <p class="cp__meta">
                    <span>Tentativas até o 1º sucesso: {{ firstSuccess(r.profile.attemptsToFirstSuccess) }}</span>
                    <span>Último sucesso: {{ r.profile.lastSuccessAt | appDate: 'date' }}</span>
                  </p>
                </li>
              }
            </ul>
          }
          @if (p.portfolioMedianAttemptsToFirstSuccess !== null) {
            <p class="cp__small">Na carteira, a mediana é de {{ int(p.portfolioMedianAttemptsToFirstSuccess) }} tentativas até o 1º atendimento.</p>
          }
        }
        @if (profile(); as p) {
          <p class="cp__small">{{ p.notice }}</p>
        }
      </section>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .cp {
      margin: 8px 0 4px;
      padding: 10px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      font-size: var(--app-text-sm);
    }
    .cp__title {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin: 0;
      font-size: var(--app-text-xs);
      font-weight: var(--app-weight-medium);
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .cp__preferred {
      margin: 6px 0 0;
      strong {
        margin-right: 6px;
      }
    }
    .cp__list {
      margin: 8px 0 0;
      padding: 0;
      list-style: none;
    }
    .cp__item {
      padding: 6px 0;
      border-top: var(--bs-border-width) solid var(--bs-border-color-translucent);
    }
    .cp__head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 2px 8px;
    }
    .cp__value {
      font-weight: var(--app-weight-medium);
    }
    .cp__value--unknown {
      font-style: italic;
      color: var(--bs-secondary-color);
    }
    .cp__kind,
    .cp__muted-inline {
      color: var(--bs-secondary-color);
    }
    .cp__rate {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      margin-left: auto;
    }
    .cp__conf {
      cursor: help;
    }
    .cp__text {
      display: flex;
      align-items: baseline;
      gap: 6px;
      margin: 2px 0 0;
      app-icon {
        flex: none;
        color: var(--bs-secondary-color);
      }
    }
    .cp__meta {
      display: flex;
      flex-wrap: wrap;
      gap: 2px 16px;
      margin: 2px 0 0;
      color: var(--bs-secondary-color);
    }
    .cp__muted {
      margin: 6px 0 0;
      color: var(--bs-secondary-color);
    }
    .cp__small {
      margin: 6px 0 0;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .cp__error {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      margin: 6px 0 0;
      color: var(--bs-danger-text-emphasis);
    }
  `,
})
export class ContactProfileCard {
  readonly debtorId = input.required<string>();
  readonly caseId = input<string | null>(null);
  /** Contatos que a tela já exibe (valor mascarado), para a junção pelo id. */
  readonly contacts = input<readonly KnownContact[]>([]);

  private readonly api = inject(ContactIntelligenceApi);
  private readonly auth = inject(AuthService);

  protected readonly id = `cp-${nextId++}`;
  protected readonly channels = CONTACT_CHANNEL_LABELS;
  protected readonly confidence = CONFIDENCE_LABELS;
  protected readonly confidenceHints = CONFIDENCE_HINTS;
  protected readonly rate = ratePercent;
  protected readonly int = integer;

  protected readonly canView = computed(() => this.auth.hasPermission(Permission.SCORING_VIEW));

  protected readonly resource = rxResource({
    params: () => (this.canView() ? { debtorId: this.debtorId(), caseId: this.caseId() } : undefined),
    // 404: devedor ou caso fora do escopo — o cartão some (não revela nada além do que a tela já mostra)
    stream: ({ params }) =>
      this.api
        .profile(params.debtorId, params.caseId)
        .pipe(catchError((err: unknown) => (err instanceof HttpErrorResponse && err.status === 404 ? of(null) : throwError(() => err)))),
  });
  protected readonly profile = computed<ContactProfileView | null>(() => (this.resource.hasValue() ? (this.resource.value() ?? null) : null));
  protected readonly hidden = computed(() => this.resource.hasValue() && this.resource.value() === null);
  protected readonly rows = computed(() => contactRows(this.profile()?.contacts ?? [], this.contacts()));
  protected readonly errorText = computed(() => {
    const e = toApiError(this.resource.error());
    return (e.code && SCORING_ERROR_LABELS[e.code]) || 'Não foi possível carregar o perfil de contato.';
  });

  protected firstSuccess(value: number | null): string {
    return value === null ? 'ainda sem sucesso' : integer(value);
  }
}
