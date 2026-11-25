import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silentErrorContext } from '../../core/http/http-context';
import { CREDITOR_API } from './creditor.api';
import { CreditorPaymentReportRequest, CreditorReportedPayment, CreditorTitleMatch } from './creditor.models';

/** Pagamentos informados no portal do credor (`/api/creditor/v1/payments`). O credor vem sempre da sessão. */
@Injectable({ providedIn: 'root' })
export class CreditorPaymentsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${CREDITOR_API}/payments`;

  list(): Observable<CreditorReportedPayment[]> {
    return this.http.get<CreditorReportedPayment[]>(this.base, { context: silentErrorContext() });
  }

  /** Título do próprio credor pelo número exato (vazio se não existe ou não aceita pagamento). */
  findTitles(number: string): Observable<CreditorTitleMatch[]> {
    return this.http.get<CreditorTitleMatch[]>(`${this.base}/titles`,
      { params: new HttpParams().set('number', number), context: silentErrorContext() });
  }

  /** Informe com comprovante e código do autenticador; a mesma chave repetida devolve o mesmo informe. */
  report(request: CreditorPaymentReportRequest, evidence: File, idempotencyKey: string): Observable<CreditorReportedPayment> {
    const body = new FormData();
    body.append('report', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    body.append('evidence', evidence, evidence.name);
    return this.http.post<CreditorReportedPayment>(this.base, body,
      { headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }), context: silentErrorContext() });
  }
}
