import { Component, DestroyRef, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/permission';
import { KeyboardShortcuts } from '../../core/keyboard/keyboard-shortcuts.service';
import { Icon } from '../../shared/components/icon/icon';
import { InfoHint } from '../../shared/components/info-hint/info-hint';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { valueOr } from '../../shared/data/resource-value';
import { MyDayApi } from '../collection/data/collection.api';

/**
 * "Seu dia" no início (decisão D8): para o operador, o botão "Próximo caso" (Alt + N) e as contagens
 * do trabalho de hoje — casos com ele, retornos de hoje e atrasados, promessas vencendo e o próprio
 * resultado do dia; para o supervisor, as equipes que ele supervisiona (filas, retornos atrasados e
 * promessas). Cada número leva à tela de trabalho. Só contagens (`GET /collection/my-day`), sem dado
 * de devedor; aprovações e conversas continuam em "Suas pendências", logo abaixo.
 */
@Component({
  selector: 'app-my-day-panel',
  imports: [RouterLink, NgbTooltip, Icon, InfoHint, Skeleton],
  template: `
    @if (visible()) {
      <section class="md" aria-labelledby="md-title" data-tour="my-day">
        <div class="md__head">
          <h2 class="app-section-title" id="md-title">Seu dia</h2>
          @if (canWork()) {
            <button type="button" class="btn btn-primary d-inline-flex align-items-center gap-2" (click)="nextCase()" ngbTooltip="Abre a estação com o caso mais prioritário (Alt + N)" data-tour="home-next-case">
              <app-icon name="skip-forward" />
              Próximo caso
              <kbd class="md__kbd" aria-hidden="true">Alt N</kbd>
            </button>
          }
        </div>

        @if (day.isLoading() && !view()) {
          <app-skeleton variant="lines" [rows]="2" />
        } @else if (day.status() === 'error') {
          <p class="app-muted md__warn" role="status">
            Não foi possível carregar o seu dia agora.
            <button type="button" class="btn btn-link btn-sm p-0 align-baseline" (click)="day.reload()">Tentar novamente</button>
          </p>
        } @else if (view(); as v) {
          @if (v.operator; as o) {
            <ul class="md__grid" aria-label="Seu trabalho hoje">
              <li class="md__tile">
                <a class="md__link" routerLink="/cobranca/fila">
                  <span class="md__label">Casos com você</span>
                  <span class="md__value app-numeric">{{ o.assignedCases }}</span>
                </a>
                <app-info-hint indicator="app.myday.cases" label="Casos com você" />
              </li>
              <li class="md__tile" [class.md__tile--late]="o.callbacksOverdue > 0">
                <a class="md__link" routerLink="/cobranca/agenda" [queryParams]="{ periodo: o.callbacksOverdue > 0 ? 'late' : 'today' }">
                  <span class="md__label">Retornos de hoje</span>
                  <span class="md__value app-numeric">{{ o.callbacksToday }}</span>
                  @if (o.callbacksOverdue > 0) {
                    <span class="md__sub md__sub--late">{{ o.callbacksOverdue }} atrasado(s)</span>
                  }
                </a>
                <app-info-hint indicator="app.myday.callbacks" label="Retornos de hoje" />
              </li>
              <li class="md__tile">
                <a class="md__link" routerLink="/cobranca/fila">
                  <span class="md__label">Promessas vencendo hoje</span>
                  <span class="md__value app-numeric">{{ o.promisesDueToday }}</span>
                  @if (o.promisesAwaitingPayment > 0) {
                    <span class="md__sub">{{ o.promisesAwaitingPayment }} vencida(s) na tolerância</span>
                  }
                </a>
                <app-info-hint indicator="app.myday.promises" label="Promessas vencendo hoje" />
              </li>
              <li class="md__tile md__tile--own">
                <div class="md__link">
                  <span class="md__label">Seu resultado hoje</span>
                  <span class="md__value app-numeric">{{ o.effectiveContactsToday }} <span class="md__of">contatos efetivos</span></span>
                  <span class="md__sub">em {{ o.interactionsToday }} interação(ões) · {{ o.promisesRecordedToday }} promessa(s)</span>
                </div>
                <app-info-hint indicator="app.myday.own-result" label="Seu resultado hoje" />
              </li>
            </ul>
          }

          @if (v.team; as t) {
            <h3 class="md__subtitle">
              Sua equipe hoje
              <span class="app-muted">· {{ t.members }} operador(es) em {{ t.teams }} equipe(s)</span>
              <app-info-hint indicator="app.myday.team" label="Sua equipe hoje" />
            </h3>
            <ul class="md__grid" aria-label="Sua equipe hoje">
              <li class="md__tile">
                <div class="md__link">
                  <span class="md__label">Casos com a equipe</span>
                  <span class="md__value app-numeric">{{ t.assignedCases }}</span>
                </div>
              </li>
              <li class="md__tile" [class.md__tile--late]="t.callbacksOverdue > 0">
                <div class="md__link">
                  <span class="md__label">Retornos atrasados</span>
                  <span class="md__value app-numeric">{{ t.callbacksOverdue }}</span>
                </div>
              </li>
              <li class="md__tile">
                <div class="md__link">
                  <span class="md__label">Promessas vencendo hoje</span>
                  <span class="md__value app-numeric">{{ t.promisesDueToday }}</span>
                </div>
              </li>
            </ul>
            @if (t.queues.length) {
              <table class="table table-sm align-middle md__queues" aria-label="Filas atendidas pelas suas equipes">
                <thead>
                  <tr>
                    <th scope="col">Fila</th>
                    <th scope="col" class="text-end">Aguardando operador</th>
                    <th scope="col" class="text-end">Com próxima ação vencida</th>
                  </tr>
                </thead>
                <tbody>
                  @for (q of t.queues; track q.queueId) {
                    <tr>
                      <td>
                        @if (canSeeQueues()) {
                          <a routerLink="/cobranca/filas">{{ q.name }}</a>
                        } @else {
                          {{ q.name }}
                        }
                      </td>
                      <td class="text-end app-numeric">{{ q.waiting }}</td>
                      <td class="text-end app-numeric" [class.md__late]="q.overdue > 0">{{ q.overdue }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          }
        }
      </section>
    }
  `,
  styles: `
    .md {
      margin-bottom: var(--app-gap);
    }
    .md__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 8px;
    }
    .md__head .app-section-title {
      margin: 0;
    }
    .md__kbd {
      margin-left: 4px;
      border-color: currentColor;
      background: transparent;
      color: inherit;
      opacity: 0.8;
    }
    .md__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 8px;
      margin: 0 0 8px;
      padding: 0;
      list-style: none;
    }
    .md__tile {
      position: relative;
      display: flex;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .md__tile app-info-hint {
      position: absolute;
      top: 6px;
      right: 8px;
    }
    .md__tile--late {
      border-left: 4px solid var(--bs-danger-text-emphasis);
    }
    .md__link {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: 2px;
      padding: 10px 32px 10px 12px;
      color: inherit;
      text-decoration: none;
    }
    a.md__link:hover .md__label {
      text-decoration: underline;
    }
    .md__label {
      font-size: var(--app-text-xs);
      font-weight: 500;
      color: var(--bs-secondary-color);
    }
    .md__value {
      font-size: var(--app-text-xl);
      font-weight: 500;
    }
    .md__of {
      font-size: var(--app-text-sm);
      font-weight: var(--bs-body-font-weight);
    }
    .md__sub {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .md__sub--late,
    .md__late {
      font-weight: 500;
      color: var(--bs-danger-text-emphasis);
    }
    .md__subtitle {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
      margin: 12px 0 8px;
      font-size: var(--app-text-sm);
      font-weight: 500;
    }
    .md__queues {
      max-width: 640px;
      font-size: var(--app-text-sm);
    }
    .md__warn {
      margin: 0;
    }
    @media (pointer: coarse) {
      .md__kbd {
        display: none;
      }
    }
  `,
})
export class MyDayPanel {
  private readonly auth = inject(AuthService);
  private readonly api = inject(MyDayApi);
  private readonly router = inject(Router);

  protected readonly canWork = computed(() => this.auth.hasPermission(Permission.CASE_WORK));
  private readonly canAssign = computed(() => this.auth.hasPermission(Permission.CASE_ASSIGN));
  protected readonly canSeeQueues = computed(() => this.auth.hasAnyPermission([Permission.QUEUE_MANAGE, Permission.CASE_ASSIGN]));

  protected readonly day = rxResource({
    params: () => (this.canWork() || this.canAssign() ? true : undefined),
    stream: () => this.api.get(),
  });
  protected readonly view = valueOr(this.day, undefined);
  /** Supervisor sem equipe supervisionada e sem `CASE_WORK`: o bloco não aparece. */
  protected readonly visible = computed(() => {
    if (!this.canWork() && !this.canAssign()) {
      return false;
    }
    const v = this.view();
    return this.canWork() || !v || !!v.team || this.day.status() === 'error';
  });

  constructor() {
    if (this.canWork()) {
      const off = inject(KeyboardShortcuts).register({
        id: 'home.next',
        keys: 'alt+n',
        group: 'Início',
        description: 'Próximo caso (abre a estação)',
        handler: () => this.nextCase(),
      });
      inject(DestroyRef).onDestroy(off);
    }
  }

  /** Abre a estação pedindo o próximo caso (a estação entrega o mais prioritário). */
  protected nextCase(): void {
    if (this.canWork()) {
      void this.router.navigate(['/cobranca/fila'], { queryParams: { proximo: 1 } });
    }
  }
}
