import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../../core/auth/auth.service';
import { ConfigLink } from '../../../../shared/components/config-link/config-link';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../../shared/components/icon/icon';
import { Skeleton } from '../../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { DataSubjectRequestsApi } from '../../data/compliance.api';
import { DataSubjectRequestView, DraftLinkView, DraftRecipientView, ResponseDraftView } from '../../data/compliance.models';
import { PROCESSORS_PERMISSIONS, PROCESSORS_ROUTE, RECIPIENT_GROUPS, canOpenLink, draftAvailability, providerConfig } from '../../data/data-sharing';

/** Texto escolhido para a conclusão e o resumo do rascunho (registra na auditoria o uso e se foi editado). */
export interface DraftUse {
  text: string;
  sha256: string;
}

/**
 * "Resposta sugerida" (art. 18, I e VII): o servidor reúne credores, prestadores, escritórios e integrações com quem
 * os dados do titular foram compartilhados e sugere o texto. A pessoa confere a tabela (cada linha leva à evidência,
 * só se ela abre a tela de destino), resolve os alertas pelos atalhos de configuração e só então usa o texto — que
 * abre editável no diálogo de conclusão. Nada é enviado sozinho.
 * <p>
 * Montar o rascunho lê dados pessoais do titular (registra acesso a dado sensível e auditoria): só acontece quando a
 * pessoa pede ("Montar resposta sugerida"), nunca ao abrir a solicitação.
 */
@Component({
  selector: 'app-response-draft-card',
  // o próprio componente é o cartão (como `creditor-dispute-contacts`): assim vale o espaçamento padrão entre cartões
  // vizinhos (`.app-card + .app-card`), que um <section> interno não recebe
  host: {
    class: 'app-card d-block',
    role: 'region',
    'aria-labelledby': 'resposta-sugerida',
    'data-tour': 'dsr-draft',
    '[attr.aria-busy]': 'draft.isLoading()',
  },
  imports: [RouterLink, ConfigLink, EmptyState, Icon, Skeleton, AppDatePipe],
  template: `
    @let r = request();
    @let state = availability();
      <div class="rd-head">
        <h2 class="app-card__title mb-0" id="resposta-sugerida">Resposta sugerida</h2>
        @if (d(); as v) {
          <span class="app-muted small">Montada em {{ v.generatedAt | appDate }}</span>
        }
      </div>
      <p class="app-muted small mb-3">
        O sistema reúne o que registra sobre o titular. Confira, ajuste se preciso e use como resposta — nada é enviado automaticamente.
      </p>

      @if (!r.identityVerifiedAt) {
        <app-empty-state icon="shield-lock" [compact]="true" title="Resposta sugerida após a identidade." [message]="state.hint ?? ''" />
      } @else if (!requested()) {
        <app-empty-state
          icon="file-earmark-text"
          [compact]="true"
          title="Resposta sugerida ainda não montada."
          message="Reúne os credores, prestadores, escritórios e integrações com quem os dados do titular foram compartilhados. A consulta fica registrada na trilha de acesso a dados pessoais."
        >
          <button class="btn btn-outline-primary" type="button" (click)="build()">
            <app-icon name="magic" />
            Montar resposta sugerida
          </button>
        </app-empty-state>
      } @else if (draft.isLoading() && !d()) {
        <app-skeleton variant="table" [rows]="4" [columns]="5" />
      } @else if (draft.status() === 'error') {
        <app-empty-state icon="cloud-slash" tone="error" [compact]="true" title="Não foi possível montar a resposta sugerida." message="Tente de novo; se persistir, responda manualmente ao concluir.">
          <button class="btn btn-outline-primary" type="button" (click)="build()">Tentar novamente</button>
        </app-empty-state>
      } @else if (d(); as v) {
        @if (v.alerts.length) {
          <ul class="rd-alerts" aria-label="Pontos a resolver antes de responder">
            @for (a of v.alerts; track $index) {
              <li class="alert d-flex gap-2 align-items-start mb-2" [class.alert-danger]="a.severity === 'DANGER'" [class.alert-warning]="a.severity === 'WARNING'">
                <app-icon [name]="a.severity === 'DANGER' ? 'exclamation-octagon' : 'exclamation-triangle'" />
                <span class="flex-grow-1">
                  {{ a.message }}
                  @switch (a.code) {
                    @case ('PROCESSOR_UNREGISTERED') {
                      <app-config-link [route]="processorsRoute" [queryParams]="{ chave: a.recipientKey! }" label="Cadastrar operador" [permissions]="processorsPermissions" />
                    }
                    @case ('PROCESSOR_NOT_CURRENT') {
                      <app-config-link [route]="processorsRoute" [queryParams]="{ chave: a.recipientKey! }" label="Revisar cadastro" [permissions]="processorsPermissions" />
                    }
                    @case ('CREDITOR_WITHOUT_LEGAL_BASIS') {
                      @if (a.recipientId) {
                        <app-config-link route="/compliance/bases-legais" [queryParams]="{ credor: a.recipientId }" label="Registrar base legal" [permissions]="processorsPermissions" />
                      }
                    }
                    @case ('SOURCE_UNAVAILABLE') {
                      <button class="btn btn-link btn-sm p-0 align-baseline" type="button" (click)="build()">Montar de novo</button>
                    }
                  }
                </span>
              </li>
            }
          </ul>
        }

        @if (!v.processing) {
          <p class="mb-3"><app-icon name="info-circle" /> O titular não foi localizado na base: não há tratamento nem compartilhamento a informar.</p>
        } @else if (v.recipients.length === 0) {
          <p class="mb-3"><app-icon name="info-circle" /> Nenhum compartilhamento registrado para este titular.</p>
        } @else {
          @if (v.type === 'CONFIRMATION' && v.dataCategories.length) {
            <p class="mb-2"><strong>Dados tratados:</strong> {{ v.dataCategories.join('; ') }}.</p>
          }
          <div class="table-responsive">
            <table class="table table-sm align-middle rd-table">
              <caption class="visually-hidden">Com quem os dados do titular foram compartilhados</caption>
              <thead>
                <tr>
                  <th scope="col">Destinatário</th>
                  <th scope="col">Finalidade</th>
                  <th scope="col">Período</th>
                  <th scope="col">Registros</th>
                  <th scope="col">Base legal ou contrato</th>
                </tr>
              </thead>
              @for (g of groups(); track g.type) {
                <tbody>
                  <tr class="rd-group">
                    <th scope="rowgroup" colspan="5">{{ g.label }}</th>
                  </tr>
                  @for (x of g.items; track x.key) {
                    <tr>
                      <td>
                        <span class="fw-medium">{{ x.name }}</span>
                        @for (link of visibleLinks(x); track link.route) {
                          <div class="small"><a [routerLink]="link.route" [queryParams]="link.queryParams">{{ link.label }}</a></div>
                        }
                      </td>
                      <td>{{ x.purposes.join('; ') }}</td>
                      <td class="text-nowrap">
                        @if (x.firstAt) {
                          {{ x.firstAt | appDate: 'date' }}@if (x.lastAt && x.lastAt !== x.firstAt) { – {{ x.lastAt | appDate: 'date' }}}
                        } @else {
                          —
                        }
                      </td>
                      <td>{{ x.countLabel || '—' }}</td>
                      <td>
                        @if (x.type === 'CREDITOR') {
                          @if (x.legalBases.length) {
                            {{ x.legalBases.join('; ') }}
                          } @else {
                            <span class="text-warning-emphasis">Sem base legal vigente</span>
                          }
                        } @else if (x.type === 'PROVIDER') {
                          @if (x.processor; as p) {
                            <a [routerLink]="processorsRoute" [queryParams]="{ chave: x.key }">{{ p.role }}</a>
                            · {{ p.countryName }}@if (p.transferSafeguard) { · {{ p.transferSafeguard }}}
                            @if (!p.current) {
                              <div class="text-warning-emphasis small">Cadastro inativo ou contrato vencido</div>
                            }
                          } @else {
                            <span class="text-warning-emphasis">Sem cadastro de operador</span>
                          }
                          @if (config(x.key); as c) {
                            <div><app-config-link [route]="c.route" [label]="c.label" [permissions]="c.permissions" /></div>
                          }
                        } @else {
                          —
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              }
            </table>
          </div>
        }

        <h3 class="h6 mt-3">Texto sugerido ao titular</h3>
        <div class="rd-preview" role="document" aria-label="Prévia do texto sugerido (somente leitura)">
          @for (p of v.paragraphs; track $index) {
            <div class="rd-paragraph">
              <p class="mb-1">{{ p.text }}</p>
              @if (p.recipientKeys.length) {
                <p class="rd-source">Vem de: {{ sources(p.recipientKeys) }}</p>
              }
            </div>
          }
        </div>
        <div class="rd-actions">
          @if (state.ready) {
            <button class="btn btn-primary" type="button" (click)="use.emit({ text: v.text, sha256: v.textSha256 })">
              <app-icon name="pencil-square" />
              Usar como resposta
            </button>
          } @else if (state.hint) {
            <span class="app-muted small"><app-icon name="info-circle" /> {{ state.hint }}</span>
          }
          <button class="btn btn-outline-secondary" type="button" (click)="build()" [disabled]="draft.isLoading()">
            <app-icon name="arrow-clockwise" />
            Montar de novo
          </button>
          <span class="app-muted small ms-auto">{{ v.text.length }} / {{ v.textMaxLength }} caracteres</span>
        </div>
      }
  `,
  styles: `
    .rd-head { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
    .rd-alerts { list-style: none; margin: 0 0 12px; padding: 0; }
    .rd-table { margin-bottom: 0; }
    .rd-group th { background: var(--app-surface-raised); font-size: var(--app-text-xs); font-weight: 600; color: var(--bs-secondary-color); }
    .rd-preview { padding: 12px 16px; border: 1px dashed var(--bs-border-color); border-radius: var(--app-radius-md); background: var(--app-surface-raised); }
    .rd-paragraph + .rd-paragraph { margin-top: 12px; }
    .rd-paragraph p { white-space: pre-line; overflow-wrap: anywhere; }
    .rd-source { margin: 0; font-size: var(--app-text-xs); color: var(--bs-secondary-color); }
    .rd-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; }
  `,
})
export class ResponseDraftCard {
  readonly request = input.required<DataSubjectRequestView>();
  /** "Usar como resposta": o detalhe abre a conclusão com o texto preenchido e editável. */
  readonly use = output<DraftUse>();

  private readonly api = inject(DataSubjectRequestsApi);
  private readonly auth = inject(AuthService);
  protected readonly processorsRoute = PROCESSORS_ROUTE;
  protected readonly processorsPermissions = PROCESSORS_PERMISSIONS;

  protected readonly availability = computed(() => draftAvailability(this.request(), this.auth.user()?.allCreditors ?? false));

  /**
   * Pedido explícito de montar (id da solicitação e contador): sem ele nada é consultado. Trocar de solicitação volta ao
   * estado inicial; "Montar de novo" incrementa o contador.
   */
  private readonly requestedFor = signal<{ id: string; n: number } | null>(null);
  protected readonly requested = computed(() => this.requestedFor()?.id === this.request().id);

  protected readonly draft = rxResource({
    params: () => {
      const r = this.request();
      const asked = this.requestedFor();
      return this.availability().show && r.identityVerifiedAt && asked?.id === r.id ? asked : undefined;
    },
    stream: ({ params }) => this.api.responseDraft(params.id),
  });
  protected readonly d = valueOr(this.draft, undefined as ResponseDraftView | undefined);

  protected readonly groups = computed(() => {
    const recipients = this.d()?.recipients ?? [];
    return RECIPIENT_GROUPS.map((g) => ({ ...g, items: recipients.filter((x) => x.type === g.type) })).filter((g) => g.items.length > 0);
  });

  private readonly has = (p: string) => this.auth.hasPermission(p);

  /** "Montar resposta sugerida" / "Montar de novo": cada clique é uma consulta registrada. */
  protected build(): void {
    const id = this.request().id;
    this.requestedFor.update((x) => ({ id, n: x?.id === id ? x.n + 1 : 1 }));
  }

  /** Links da evidência que a pessoa pode abrir (menus e links sem permissão ficam ocultos). */
  protected visibleLinks(x: DraftRecipientView): DraftLinkView[] {
    this.auth.user();
    return x.links.filter((l) => canOpenLink(l, this.has));
  }

  protected config(key: string) {
    return providerConfig(key);
  }

  protected sources(keys: string[]): string {
    const names = new Map((this.d()?.recipients ?? []).map((x) => [x.key, x.name]));
    return keys.map((k) => names.get(k) ?? k).join(', ');
  }
}
