import { Injectable, computed, inject, signal, untracked } from '@angular/core';
import { rxResource, toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, from, map, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { BUSINESS_ZONE, zonedNow } from '../../collection/data/collection.rules';
import { QualityApi } from '../data/incentive.api';
import { EvaluationView, OwnEvaluation, QualityForm, ReferenceType } from '../data/incentive.models';
import { score } from '../data/incentive.rules';

/**
 * Atendimento a avaliar, vindo da tela em que ele aparece (chamada, conversa, histórico do caso): o formulário de
 * monitoria abre com o negociador, a referência e a data preenchidos — sem digitar identificador (J7).
 */
export interface EvaluationTarget {
  /** Quem atendeu (o negociador avaliado). */
  userId: string;
  /** Nome de quem atendeu, só para exibir. */
  userName: string;
  referenceType: ReferenceType;
  referenceId: string;
  /** Data do atendimento (`yyyy-MM-dd`, fuso de operação). */
  evaluatedOn: string;
  /** Descrição curta do atendimento ("Chamada de 07/11/2026 14:32"). */
  label: string;
}

/** Dados do diálogo de monitoria: os formulários ativos e, quando vem do atendimento, o que já está preenchido. */
export interface EvaluationDialogData {
  forms: QualityForm[];
  prefill?: EvaluationTarget;
}

/** Data local (fuso de operação) de um instante ISO — a data do atendimento na monitoria. */
export function attendanceDate(instant: string): string {
  return zonedNow(new Date(instant)).date;
}

/** Atendimentos por consulta das próprias avaliações (o servidor aceita até 200). */
const OWN_BATCH = 200;

const ownKey = (type: ReferenceType, id: string): string => `${type}:${id}`;

const DAY = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeZone: BUSINESS_ZONE });

/** Selo que substitui "Avaliar" quando a própria pessoa já avaliou o atendimento. */
export function ownEvaluationText(e: OwnEvaluation): string {
  return `Avaliado por você · nota ${score(e.score)}`;
}

/** Dica do selo: quando foi a avaliação e que outro avaliador ainda pode avaliar o atendimento. */
export function ownEvaluationHint(e: OwnEvaluation): string {
  return `Você registrou a monitoria deste atendimento em ${DAY.format(new Date(e.createdAt))}${e.criticalFailure ? ' (falha em critério crítico)' : ''}. Cada avaliador avalia um atendimento uma vez; outro avaliador ainda pode avaliá-lo.`;
}

/**
 * "Avaliar este atendimento": regra de exibição e abertura do diálogo de monitoria já preenchido. Exige
 * `QUALITY_EVALUATE` e que quem atendeu esteja no escopo de quem avalia — todos, com visão total, ou os membros das
 * equipes que supervisiona —, informado pelo servidor (`GET /quality/evaluable-users`, a mesma regra que ele aplica ao
 * registrar); até a resposta chegar, o botão não aparece. Ninguém avalia o próprio atendimento. Atendimento sem pessoa
 * (assistente, sistema) não é avaliado aqui. Cada avaliador avalia um atendimento uma vez: o que a pessoa já avaliou
 * mostra "Avaliado por você" no lugar do botão (`trackOwn`/`ownEvaluation`; o servidor recusa a segunda avaliação).
 * O diálogo é carregado sob demanda (fica no pacote da monitoria).
 */
@Injectable({ providedIn: 'root' })
export class EvaluationLauncher {
  private readonly auth = inject(AuthService);
  private readonly api = inject(QualityApi);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);

  /** Escopo de quem avalia, carregado uma vez por pessoa logada (falha = ninguém, o botão não aparece). */
  private readonly scope = rxResource({
    params: () => (this.auth.hasPermission(Permission.QUALITY_EVALUATE) ? (this.auth.user()?.id ?? undefined) : undefined),
    stream: () => this.api.evaluableUsers().pipe(catchError(() => of({ all: false, userIds: [] }))),
  });
  private readonly evaluable = computed(() => {
    const s = this.scope.hasValue() ? this.scope.value() : null;
    return s ? { all: s.all, ids: new Set(s.userIds) } : null;
  });

  /** Escopo de quem avalia (`null` até a resposta do servidor), para o seletor de negociador. */
  readonly scope$ = toObservable(this.evaluable);

  /** Valor atual do escopo (`null` até a resposta). */
  scopeSnapshot(): { all: boolean; ids: ReadonlySet<string> } | null {
    return this.evaluable();
  }

  /** Mostra "Avaliar" para este atendimento: permissão de avaliar, atendente no escopo e que não é a própria pessoa. */
  canEvaluate(attendantId: string | null | undefined): boolean {
    if (!attendantId || !this.auth.hasPermission(Permission.QUALITY_EVALUATE) || attendantId === this.auth.user()?.id) {
      return false;
    }
    const e = this.evaluable();
    return !!e && (e.all || e.ids.has(attendantId));
  }

  /** Avaliações da própria pessoa por atendimento: consultadas sob demanda pelas telas e somadas ao registrar. */
  private readonly own = signal<ReadonlyMap<string, OwnEvaluation>>(new Map());
  /** Atendimentos já consultados (ou em consulta) para a pessoa `ownOf`. */
  private readonly asked = new Set<string>();
  private ownOf: string | null = null;

  /**
   * Consulta, para os atendimentos da tela, quais a própria pessoa já avaliou (só os ainda não consultados, em lotes).
   * Chamada pelas telas em `effect` com os atendimentos que mostram "Avaliar"; não cria dependência de sinal.
   */
  trackOwn(type: ReferenceType, ids: readonly string[]): void {
    untracked(() => {
      const userId = this.auth.user()?.id ?? null;
      if (!userId || !this.auth.hasPermission(Permission.QUALITY_EVALUATE)) {
        return;
      }
      if (userId !== this.ownOf) {
        this.ownOf = userId;
        this.asked.clear();
        this.own.set(new Map());
      }
      const pending = [...new Set(ids)].filter((id) => !this.asked.has(ownKey(type, id)));
      for (let i = 0; i < pending.length; i += OWN_BATCH) {
        const batch = pending.slice(i, i + OWN_BATCH);
        batch.forEach((id) => this.asked.add(ownKey(type, id)));
        this.api.ownEvaluations(type, batch).subscribe({
          next: (list) => userId === this.ownOf && this.remember(list),
          // falha: o botão continua (o servidor recusa a duplicata) e a próxima exibição consulta de novo
          error: () => batch.forEach((id) => this.asked.delete(ownKey(type, id))),
        });
      }
    });
  }

  /** A avaliação que a própria pessoa já registrou para este atendimento (`null` se não avaliou ou ainda não se sabe). */
  ownEvaluation(type: ReferenceType, id: string | null | undefined): OwnEvaluation | null {
    return id ? (this.own().get(ownKey(type, id)) ?? null) : null;
  }

  private remember(list: OwnEvaluation[]): void {
    if (list.length) {
      this.own.update((m) => {
        const next = new Map(m);
        list.forEach((e) => next.set(ownKey(e.referenceType, e.referenceId), e));
        return next;
      });
    }
  }

  /** Abre a monitoria preenchida; sem formulário ativo, avisa onde criar. Emite a avaliação registrada (ou nada). */
  open(target: EvaluationTarget): Observable<EvaluationView | undefined> {
    if (!this.canEvaluate(target.userId) || this.ownEvaluation(target.referenceType, target.referenceId)) {
      return of(undefined);
    }
    return this.api.forms().pipe(
      map((forms) => forms.filter((f) => f.active)),
      switchMap((forms) => {
        if (!forms.length) {
          this.notifications.info('Nenhum formulário de monitoria ativo: crie um em Monitoria de qualidade › Formulários.');
          return of(undefined);
        }
        return from(import('./evaluation-list')).pipe(
          switchMap(({ EvaluationDialog }) =>
            this.dialog.open<InstanceType<typeof EvaluationDialog>, EvaluationDialogData, EvaluationView>(EvaluationDialog, { data: { forms, prefill: target }, size: 'lg' }).afterClosed(),
          ),
          map((saved) => {
            if (saved?.referenceId) {
              this.remember([{ evaluationId: saved.id, referenceType: saved.referenceType, referenceId: saved.referenceId, score: saved.score, criticalFailure: saved.criticalFailure, createdAt: saved.createdAt }]);
            } else if (!saved) {
              // fechou sem registrar (talvez recusado por já ter sido avaliado em outra aba): confere de novo
              this.asked.delete(ownKey(target.referenceType, target.referenceId));
              this.trackOwn(target.referenceType, [target.referenceId]);
            }
            if (saved) {
              this.notifications.success(`Monitoria registrada: nota ${score(saved.score)}. Ela aparece em Monitoria de qualidade, com o link para este atendimento.`);
            }
            return saved;
          }),
        );
      }),
    );
  }
}
