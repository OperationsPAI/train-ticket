import { createHash } from "node:crypto";
import { tracedFetch } from "@trainticket/ts-kit";
import { DomainError, type AncillaryOrderItemSnapshot, type Money } from "./domain.js";
import { ReferenceUnavailable } from "./references.js";

export type PaymentDetails = { paymentIntentId: string; businessRef: string; purpose: string; payerRef: string; status: string; amount: Money; capturedAmount: Money; refundedAmount: Money };
export class HttpAncillaryPayments {
  constructor(private readonly base = process.env.PAYMENT_URL ?? "http://payment:8080", private readonly orders = process.env.JOURNEY_ORDER_URL ?? "http://journey-order:8080") {}
  async prepare(item: AncillaryOrderItemSnapshot, correlation: string): Promise<string> {
    const order = await this.request(this.orders, `/api/v1/journey-orders/${encodeURIComponent(item.journeyOrderId)}`, correlation);
    if (order.status !== "CONFIRMED" || !order.accountId) throw new DomainError("ORDER_NOT_CONFIRMED","Payment requires a confirmed order");
    let payment = await this.request(this.base, `/api/v1/payment-intents/by-business/${encodeURIComponent(item.ancillaryOrderItemId)}`, correlation, undefined, true);
    if (!payment) payment = await this.request(this.base, "/api/v1/payment-intents", correlation,
      {businessRef:item.ancillaryOrderItemId,purpose:"ancillary",payerRef:order.accountId,amount:item.payableAmount,preferredChannel:"ALIPAY_SIM"},false,paymentKey(item.ancillaryOrderItemId));
    const details = await this.details(payment.paymentIntentId,correlation);
    this.matches(item,details,order.accountId);
    return details.paymentIntentId;
  }
  async verify(item: AncillaryOrderItemSnapshot, correlation: string): Promise<PaymentDetails> {
    if (!item.paymentIntentId) throw new DomainError("PAYMENT_REQUIRED","Ancillary item requires its own payment intent");
    const payment = await this.details(item.paymentIntentId,correlation);
    const order = await this.request(this.orders,`/api/v1/journey-orders/${encodeURIComponent(item.journeyOrderId)}`,correlation);
    this.matches(item,payment,order.accountId);
    if (payment.status!=="CAPTURED" || !sameMoney(payment.capturedAmount,item.payableAmount) || payment.refundedAmount.currency!==item.payableAmount.currency || payment.refundedAmount.minorUnits!==0)
      throw new DomainError("PAYMENT_NOT_CAPTURED","Ancillary payment is not fully captured or has been refunded");
    return payment;
  }
  details(id: string,correlation: string): Promise<PaymentDetails> { return this.request(this.base,`/api/v1/payment-intents/${encodeURIComponent(id)}`,correlation); }
  async validateLatePayment(item:AncillaryOrderItemSnapshot,correlation:string):Promise<void>{
    if(!item.paymentIntentId)throw new DomainError("PAYMENT_REQUIRED","Ancillary item requires its own payment intent");
    const payment=await this.details(item.paymentIntentId,correlation);
    const order=await this.request(this.orders,`/api/v1/journey-orders/${encodeURIComponent(item.journeyOrderId)}`,correlation);
    this.matches(item,payment,order.accountId);
    if(!["EXPIRED","CANCELLED"].includes(payment.status))throw new DomainError("PAYMENT_NOT_TERMINAL","Late payment resolution requires an expired or cancelled payment");
    const late=await this.request(this.base,`/api/v1/payment-intents/${encodeURIComponent(item.paymentIntentId)}/late-payment`,correlation);
    if(!sameMoney(late.capturedAmount,item.payableAmount))throw new DomainError("PAYMENT_MISMATCH","Late collection must match ancillary payable amount");
  }
  async resolveLatePayment(item:AncillaryOrderItemSnapshot,correlation:string):Promise<any>{
    await this.validateLatePayment(item,correlation);
    await this.request(this.base,`/api/v1/payment-intents/${encodeURIComponent(item.paymentIntentId!)}/late-payment/refund`,correlation,
      {businessRef:item.ancillaryOrderItemId,businessCaseRef:`ancillary:${item.ancillaryOrderItemId}`,reason:"ANCILLARY_LATE_PAYMENT"},false,paymentKey("late-refund:"+item.ancillaryOrderItemId));
    const refund=await this.refundFor(item,correlation);
    if(!refund)throw new ReferenceUnavailable("Late payment refund has not settled");
    return this.settledRefund(item,refund.refundId,correlation);
  }
  async refundFor(item:AncillaryOrderItemSnapshot,correlation:string):Promise<any|undefined>{
    const response=await this.request(this.base,`/api/v1/refunds?businessCaseRef=${encodeURIComponent("ancillary:"+item.ancillaryOrderItemId)}`,correlation);
    return response.items?.find((refund:any)=>refund.paymentIntentId===item.paymentIntentId && refund.status==="SETTLED");
  }
  async settledRefund(item:AncillaryOrderItemSnapshot,refundId:string,correlation:string):Promise<any>{
    const refund=await this.request(this.base,`/api/v1/refunds/${encodeURIComponent(refundId)}`,correlation);
    if(refund.paymentIntentId!==item.paymentIntentId || refund.businessCaseRef!==`ancillary:${item.ancillaryOrderItemId}` || refund.status!=="SETTLED" || !sameMoney(refund.amount,item.payableAmount))
      throw new DomainError("REFUND_MISMATCH","Refund must be settled for this ancillary payment and amount");
    return refund;
  }
  private matches(item:AncillaryOrderItemSnapshot,payment:PaymentDetails,payer:string):void{
    if(payment.businessRef!==item.ancillaryOrderItemId || payment.purpose!=="ancillary" || payment.payerRef!==payer || !sameMoney(payment.amount,item.payableAmount))
      throw new DomainError("PAYMENT_MISMATCH","Payment does not match ancillary business reference, payer, purpose or amount");
  }
  private async request(base:string,path:string,correlation:string,body?:unknown,allowMissing=false,key?:string):Promise<any>{
    let response:Response;
    try { response=await tracedFetch(base.replace(/\/$/,"")+path,{method:body?"POST":"GET",body:body?JSON.stringify(body):undefined,headers:{"content-type":"application/json","x-correlation-id":correlation,...(key?{"idempotency-key":key}:{})},signal:AbortSignal.timeout(8000)}); }
    catch(error){throw new ReferenceUnavailable(`Payment dependency unavailable: ${String(error)}`);}
    if(response.status===404 && allowMissing)return undefined;
    if(!response.ok)throw new ReferenceUnavailable(`Payment dependency returned ${response.status}`);
    return response.json();
  }
}
function sameMoney(a:Money,b:Money):boolean{return !!a && !!b && a.currency===b.currency && Number.isSafeInteger(a.minorUnits) && a.minorUnits===b.minorUnits;}
function paymentKey(item:string):string{
  const bytes=createHash("sha256").update("ancillary-payment:"+item).digest().subarray(0,16);bytes[6]=(bytes[6]&15)|112;bytes[8]=(bytes[8]&63)|128;
  const hex=bytes.toString("hex");return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
