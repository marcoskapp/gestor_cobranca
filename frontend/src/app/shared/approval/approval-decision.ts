import { Component, computed, input, output } from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { Icon } from '../components/icon/icon';
import { QueuePosition, positionText } from './approval-queue';

/**
 * Um ponto em que o pedido passa do limite de quem pediu (linha de "Por que precisa de aprovação").
 * Os textos já vêm formatados pela tela (valor, %, quantidade): o componente só organiza.
 */
export interface ApprovalReason {
  /** O que foi medido ("Valor do acordo", "Pagamento informado"). */
  label: string;
  /** Explicação curta abaixo do rótulo (ex.: "fração do desconto máximo da política"). */
  hint?: string;
  /** O que foi pedido. */
  requested: string;
  /** Limite de quem pediu. */
  limit: string;
  /** Quanto passa, em valor e % (nulo quando não se aplica: "qualquer aumento"). */
  excess: string | null;
  /** Limite de quem decide (nulo = não se aplica ou não informado). */
  approverLimit?: string | null;
  /** O limite de quem decide cobre este ponto (nulo = não informado). */
  covered?: boolean | null;
}

/** Quem pediu × quem decide. */
export interface ApprovalParty {
  name: string;
  /** Papel ou alçada traduzida ("Negociador júnior"). */
  role?: string | null;
}

/**
 * Bloco de decisão comum às filas de aprovação (propostas, pagamentos informados, correções de valor — ux.md §18):
 * posição na fila ("1 de N") com retorno à fila filtrada, por que precisa de aprovação com quanto passa do limite,
 * quem pediu × quem decide e as ações "Aprovar e abrir a próxima" / "Reprovar e abrir a próxima". A tela projeta os
 * detalhes (condições, memória de cálculo, comprovante) e confirma a decisão (motivo, resumo do impacto); o servidor
 * revalida tudo. Sem permissão de decidir, as ações não aparecem; quando a pessoa pode decidir em geral mas não este
 * item (segregação ou alçada), `blockedReason` explica e as ações somem.
 */
@Component({
  selector: 'app-approval-decision',
  imports: [RouterLink, Icon],
  template: `
    <section class="app-card ad" aria-labelledby="ad-title" data-tour="approval-decision">
      @if (queueLink(); as link) {
        <div class="ad-bar">
          <a class="btn btn-sm btn-outline-secondary d-inline-flex align-items-center gap-1" [routerLink]="link" [queryParams]="queueParams()">
            <app-icon name="arrow-left" />
            {{ queueLabel() }}
          </a>
          @if (counter(); as text) {
            <span class="ad-counter app-numeric" aria-live="polite" data-testid="approval-position">{{ itemLabel() }} {{ text }}</span>
          }
        </div>
      }

      <h2 class="app-card__title" id="ad-title">Por que precisa de aprovação</h2>
      @if (reasons().length) {
        <div class="table-responsive">
          <table class="table table-sm align-middle mb-0 ad-table" aria-describedby="ad-title">
            <thead>
              <tr>
                <th scope="col">Critério</th>
                <th scope="col" class="text-end">Pedido</th>
                <th scope="col" class="text-end">Limite de quem pediu</th>
                <th scope="col" class="text-end">Passa em</th>
                @if (showApprover()) {
                  <th scope="col" class="text-end">{{ approverColumn() }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (r of reasons(); track r.label) {
                <tr>
                  <th scope="row" class="fw-normal">
                    {{ r.label }}
                    @if (r.hint) {
                      <div class="app-muted ad-hint">{{ r.hint }}</div>
                    }
                  </th>
                  <td class="text-end app-numeric">{{ r.requested }}</td>
                  <td class="text-end app-numeric">{{ r.limit }}</td>
                  <td class="text-end app-numeric ad-excess">{{ r.excess ?? '—' }}</td>
                  @if (showApprover()) {
                    <td class="text-end app-numeric">
                      {{ r.approverLimit ?? '—' }}
                      @if (r.covered === true) {
                        <span class="ad-ok"><app-icon name="check-circle" label="cobre" /></span>
                      } @else if (r.covered === false) {
                        <span class="ad-no"><app-icon name="x-circle" label="não cobre" /></span>
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      @if (notes().length) {
        <ul class="ad-notes">
          @for (n of notes(); track n) {
            <li>{{ n }}</li>
          }
        </ul>
      }

      <dl class="ad-people">
        <div>
          <dt>Pedido por</dt>
          <dd>{{ requester().name }}@if (requester().role) {<span class="app-muted"> · {{ requester().role }}</span>}</dd>
        </div>
        @if (approver(); as a) {
          <div>
            <dt>Decide</dt>
            <dd>{{ a.name }}@if (a.role) {<span class="app-muted"> · {{ a.role }}</span>}</dd>
          </div>
        }
      </dl>

      @if (blockedReason(); as reason) {
        <div class="alert alert-secondary d-flex gap-2 align-items-start mb-0" role="note">
          <app-icon name="lock" />
          <span>{{ reason }}</span>
        </div>
      } @else if (canDecide()) {
        @if (approveBlockedReason(); as reason) {
          <div class="alert alert-secondary d-flex gap-2 align-items-start mb-0" role="note" data-testid="approval-approve-blocked">
            <app-icon name="lock" />
            <span>{{ reason }}</span>
          </div>
        }
        <div class="ad-actions">
          <button type="button" class="btn btn-outline-secondary" (click)="reject.emit()" [disabled]="busy()" data-testid="approval-reject">
            {{ rejectLabel() }}{{ suffix() }}
          </button>
          @if (!approveBlockedReason()) {
            <button type="button" class="btn btn-primary d-inline-flex align-items-center gap-2" (click)="approve.emit()" [disabled]="busy()" data-tour="approval-approve" data-testid="approval-approve">
              <app-icon name="patch-check" />
              {{ approveLabel() }}{{ suffix() }}
            </button>
          }
        </div>
      }
    </section>
  `,
  styles: `
    .ad {
      display: grid;
      gap: 12px;
    }
    .ad-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .ad-counter {
      font-weight: var(--app-weight-semibold);
      color: var(--bs-secondary-color);
    }
    .ad-hint {
      font-size: var(--app-text-xs);
    }
    .ad-excess {
      font-weight: var(--app-weight-semibold);
      color: var(--bs-warning-text-emphasis);
    }
    .ad-ok {
      color: var(--bs-success-text-emphasis);
      margin-inline-start: 4px;
    }
    .ad-no {
      color: var(--bs-danger-text-emphasis);
      margin-inline-start: 4px;
    }
    .ad-notes {
      margin: 0;
      padding-inline-start: 1.25rem;
    }
    .ad-people {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 32px;
      margin: 0;
      dt {
        font-size: var(--app-text-xs);
        color: var(--bs-secondary-color);
        font-weight: var(--app-weight-semibold);
      }
      dd {
        margin: 0;
      }
    }
    .ad-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px;
    }
  `,
})
export class ApprovalDecision {
  /** Posição na fila filtrada (nulo: sem contador). */
  readonly position = input<QueuePosition | null>(null);
  /** Há outro item pendente na fila depois deste. */
  readonly hasNext = input(false);
  /** Endereço da fila: com ele, a barra "Voltar para a fila" e o "… e abrir a próxima" (tela aberta pela fila). */
  readonly queueLink = input<string | null>(null);
  readonly queueParams = input<Params>({});
  readonly queueLabel = input('Voltar para a fila');
  /** Nome do item no contador ("Proposta 2 de 7"). */
  readonly itemLabel = input('Item');
  readonly reasons = input<readonly ApprovalReason[]>([]);
  /** Motivos sem medida (códigos traduzidos quando o servidor não tem o limite). */
  readonly notes = input<readonly string[]>([]);
  readonly approverColumn = input('Sua alçada');
  readonly requester = input.required<ApprovalParty>();
  readonly approver = input<ApprovalParty | null>(null);
  /** A pessoa tem a permissão de decidir (sem ela, as ações não aparecem). */
  readonly canDecide = input(false);
  /** Motivo de não poder decidir este item (segregação, alçada): substitui as ações. */
  readonly blockedReason = input<string | null>(null);
  /** Motivo de não poder aprovar (ex.: alçada insuficiente) quando reprovar continua permitido: só "Aprovar" some. */
  readonly approveBlockedReason = input<string | null>(null);
  readonly busy = input(false);
  readonly approveLabel = input('Aprovar');
  readonly rejectLabel = input('Reprovar');
  readonly approve = output<void>();
  readonly reject = output<void>();

  protected readonly counter = computed(() => positionText(this.position()));
  /** Complemento dos botões na fila: abre a próxima ou volta à fila quando esta é a última. */
  protected readonly suffix = computed(() => (!this.queueLink() ? '' : this.hasNext() ? ' e abrir a próxima' : ' e voltar à fila'));
  protected readonly showApprover = computed(() => this.reasons().some((r) => r.approverLimit !== undefined && r.approverLimit !== null));
}
