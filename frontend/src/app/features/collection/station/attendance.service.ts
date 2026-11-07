import { Injectable, effect, inject, untracked } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';
import { DisputeType, InteractionChannel, InteractionDirection } from '../data/collection.models';

/** Rascunho do registro de interação (o mesmo formato do formulário do painel). */
export interface InteractionDraft {
  channel: InteractionChannel;
  direction: InteractionDirection;
  contactId: string;
  tabulationId: string;
  note: string;
  promiseAmount: number | null;
  promiseDate: string | null;
  callbackDate: string | null;
  callbackTime: string;
  completesTaskId: string;
  disputeType: DisputeType | '';
}

interface Attendance {
  /** Início da contagem do atendimento; nulo quando outro caso foi aberto depois deste. */
  startedAt: Date | null;
  draft: InteractionDraft | null;
  tab: number;
  /** Acordo formalizado na estação (cartão "Acordo formalizado" ao voltar ao caso). */
  agreementId: string | null;
  /** Última atividade no atendimento (abrir, digitar, trocar de aba, sair da estação). */
  touchedAt: Date | null;
}

/** Casos guardados ao mesmo tempo (os mais antigos saem primeiro). */
export const ATTENDANCE_LIMIT = 20;

/**
 * Teto de pausa: voltar a um atendimento parado há mais que isso (sem abrir, digitar, trocar de aba
 * nem sair da estação) recomeça a contagem — o rascunho continua. Evita que o almoço ou o fim do
 * turno entre na duração da interação. Decisão da revisão da frente C (RC-08).
 */
export const ATTENDANCE_IDLE_MS = 30 * 60 * 1000;

/**
 * Atendimento em curso por caso (J1 da revisão de produto de 2026-11): início da contagem, rascunho
 * do registro de interação e aba ativa sobrevivem a abrir a ficha do devedor, o simulador ou outra
 * tela e voltar.
 *
 * - O atendimento é do caso aberto: voltar ao mesmo caso retoma a contagem; abrir outro caso
 *   encerra a contagem do anterior (o rascunho dele fica guardado até ser registrado).
 * - Registrar a interação limpa o rascunho e reinicia a contagem (a próxima interação começa agora).
 * - Devolver o caso à fila descarta o atendimento.
 * - Atendimento parado há mais de `ATTENDANCE_IDLE_MS` (30 min) recomeça a contagem ao voltar.
 *
 * Só em memória: a observação pode conter dado pessoal e nunca vai para `localStorage`; tudo é
 * descartado ao sair da sessão (troca de usuário ou recarga da página).
 */
@Injectable({ providedIn: 'root' })
export class AttendanceService {
  private readonly entries = new Map<string, Attendance>();
  private activeCaseId: string | null = null;

  constructor() {
    const auth = inject(AuthService);
    let userId: string | null | undefined;
    effect(() => {
      const id = auth.user()?.id ?? null;
      untracked(() => {
        if (userId !== undefined && id !== userId) {
          this.clear();
        }
        userId = id;
      });
    });
  }

  /**
   * Abre (ou retoma) o atendimento do caso e devolve o início da contagem. O mesmo caso aberto de
   * novo mantém o início; outro caso começa agora e encerra a contagem do anterior.
   */
  begin(caseId: string, now: Date = new Date()): Date {
    const entry = this.entry(caseId);
    if (entry.touchedAt && now.getTime() - entry.touchedAt.getTime() > ATTENDANCE_IDLE_MS) {
      entry.startedAt = null;
    }
    entry.touchedAt = now;
    if (this.activeCaseId !== caseId) {
      const previous = this.activeCaseId ? this.entries.get(this.activeCaseId) : undefined;
      if (previous) {
        previous.startedAt = null;
      }
      this.activeCaseId = caseId;
      entry.startedAt = null;
    }
    entry.startedAt ??= now;
    return entry.startedAt;
  }

  startedAt(caseId: string): Date | null {
    return this.entries.get(caseId)?.startedAt ?? null;
  }

  draft(caseId: string): InteractionDraft | null {
    return this.entries.get(caseId)?.draft ?? null;
  }

  saveDraft(caseId: string, draft: InteractionDraft, now: Date = new Date()): void {
    const entry = this.entry(caseId);
    entry.draft = { ...draft };
    entry.touchedAt = now;
  }

  /** Saída da estação (ou outra atividade): a pausa do teto conta a partir daqui. */
  touch(caseId: string, now: Date = new Date()): void {
    const entry = this.entries.get(caseId);
    if (entry) {
      entry.touchedAt = now;
    }
  }

  /** Canal do atendimento em curso (o do rascunho), para pré-preencher simulação e aceite. */
  channel(caseId: string): InteractionChannel | null {
    return this.entries.get(caseId)?.draft?.channel ?? null;
  }

  tab(caseId: string): number | null {
    return this.entries.get(caseId)?.tab ?? null;
  }

  setTab(caseId: string, tab: number, now: Date = new Date()): void {
    const entry = this.entry(caseId);
    entry.tab = tab;
    entry.touchedAt = now;
  }

  agreement(caseId: string): string | null {
    return this.entries.get(caseId)?.agreementId ?? null;
  }

  setAgreement(caseId: string, agreementId: string): void {
    this.entry(caseId).agreementId = agreementId;
  }

  /** Interação registrada: rascunho limpo (o canal fica) e a próxima contagem começa agora. */
  recorded(caseId: string, now: Date = new Date()): Date {
    const entry = this.entry(caseId);
    entry.draft = entry.draft ? { ...emptyDraft(), channel: entry.draft.channel } : null;
    entry.startedAt = now;
    entry.touchedAt = now;
    this.activeCaseId = caseId;
    return now;
  }

  /** Caso devolvido à fila (ou fora do alcance do usuário): o atendimento é descartado. */
  discard(caseId: string): void {
    this.entries.delete(caseId);
    if (this.activeCaseId === caseId) {
      this.activeCaseId = null;
    }
  }

  clear(): void {
    this.entries.clear();
    this.activeCaseId = null;
  }

  private entry(caseId: string): Attendance {
    let entry = this.entries.get(caseId);
    if (entry) {
      // mais recente por último (ordem de inserção do Map)
      this.entries.delete(caseId);
    } else {
      entry = { startedAt: null, draft: null, tab: 0, agreementId: null, touchedAt: null };
    }
    this.entries.set(caseId, entry);
    while (this.entries.size > ATTENDANCE_LIMIT) {
      const oldest = this.entries.keys().next().value as string;
      this.discard(oldest);
    }
    return entry;
  }
}

export function emptyDraft(): InteractionDraft {
  return {
    channel: 'PHONE',
    direction: 'OUTBOUND',
    contactId: '',
    tabulationId: '',
    note: '',
    promiseAmount: null,
    promiseDate: null,
    callbackDate: null,
    callbackTime: '',
    completesTaskId: '',
    disputeType: '',
  };
}
