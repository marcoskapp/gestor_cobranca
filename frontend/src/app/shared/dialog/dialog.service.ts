import { Injectable, Injector, Type, inject } from '@angular/core';
import { NgbModal, NgbModalOptions } from '@ng-bootstrap/ng-bootstrap';
import { DIALOG_DATA, DialogRef } from './dialog-ref';

export interface DialogConfig<D = unknown> {
  /** Injetado no componente por `DIALOG_DATA`. */
  data?: D;
  /** Largura: `sm` 400px, `md` 560px (padrão), `lg` 800px, `xl` 1140px. */
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Tela cheia sempre (`true`) ou abaixo do breakpoint informado (ex.: `'md'`). */
  fullscreen?: boolean | 'sm' | 'md' | 'lg' | 'xl' | 'xxl';
  /** Impede fechar por Esc ou clique no fundo (ex.: operação em andamento). */
  disableClose?: boolean;
  /** `alertdialog` para confirmações que interrompem o fluxo (ações destrutivas). */
  role?: 'dialog' | 'alertdialog';
  centered?: boolean;
  /** Classe na janela `.modal` (ex.: para ajustar `--bs-modal-*`). */
  windowClass?: string;
  /** Injetor pai (ex.: para serviços fornecidos pela tela). */
  injector?: Injector;
}

/**
 * Abre diálogos sobre `NgbModal` (ng-bootstrap) com a mesma ergonomia do `MatDialog`:
 * `open(Componente, { data })` → `DialogRef` com `afterClosed()`. O componente injeta
 * `DIALOG_DATA` e `DialogRef`, e usa a marcação do modal do Bootstrap
 * (`modal-header`/`modal-body`/`modal-footer`) com `appDialogTitle` e `appDialogClose`.
 *
 * Acessibilidade (do NgbModal): foco preso no diálogo, primeiro elemento `ngbAutofocus` (ou o
 * primeiro focável) recebe o foco, Esc fecha, foco devolvido ao elemento de origem.
 */
@Injectable({ providedIn: 'root' })
export class DialogService {
  private readonly modal = inject(NgbModal);
  private readonly injector = inject(Injector);

  open<C, D = unknown, R = unknown>(component: Type<C>, config: DialogConfig<D> = {}): DialogRef<R, C> {
    const ref = new DialogRef<R, C>();
    const injector = Injector.create({
      providers: [
        { provide: DIALOG_DATA, useValue: config.data },
        { provide: DialogRef, useValue: ref },
      ],
      parent: config.injector ?? this.injector,
    });
    const options: NgbModalOptions = {
      injector,
      ariaLabelledBy: ref.titleId,
      size: config.size && config.size !== 'md' ? config.size : undefined,
      fullscreen: config.fullscreen,
      backdrop: config.disableClose ? 'static' : true,
      keyboard: !config.disableClose,
      role: config.role ?? 'dialog',
      centered: config.centered,
      windowClass: config.windowClass,
    };
    const modalRef = this.modal.open(component, options);
    ref.attach(
      modalRef.componentInstance as C,
      (result) => modalRef.close(result),
      () => modalRef.dismiss(),
      modalRef.result,
    );
    return ref;
  }

  /** Há algum diálogo aberto (ex.: atalhos globais não devem abrir outro por cima). */
  hasOpenDialogs(): boolean {
    return this.modal.hasOpenModals();
  }

  /** Fecha todos os diálogos (ex.: ao encerrar a sessão). */
  closeAll(): void {
    this.modal.dismissAll();
  }
}
