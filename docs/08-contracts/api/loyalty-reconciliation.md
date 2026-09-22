# Payment points reconciliation

`POST /internal/payments/{paymentIntentId}/reconcile` restores missing purchase points for one payment.
The service reads the original capture event from Payment and verifies the payer against the order owner.
The request accepts no amount, account override, or substitute event ID.
Only captured purchase payments without refunds are eligible.
The response contains `paymentIntentId`, `sourceEventIds`, and the resulting member details.
Repeated requests use the original capture event ID and do not create another ledger entry.
Ledger changes and points events are committed in the same transaction under the member account lock.
