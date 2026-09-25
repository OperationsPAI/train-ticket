{{/*
Recording rules for the bounded OTLP application metric surface.

The runtime rules produce data immediately from the language kits. The domain
rules define stable queries for metrics that owning services add incrementally.
An absent source remains absent: never use `or vector(0)` for an uninstrumented
business invariant, because that would report an unknown condition as healthy.
*/}}
{{- define "train-ticket.prometheusApplicationRules" -}}
groups:
  - name: train-ticket-runtime
    interval: 30s
    rules:
      - record: train_ticket:http_requests:rate5m
        expr: |
          sum by (namespace, service, http_route, http_request_method, http_response_status_code) (
            rate(http_server_request_duration_seconds_count{job="train-ticket-applications"}[5m])
          )
      - record: train_ticket:http_5xx:ratio5m
        expr: |
          (
            sum by (namespace, service) (
              rate(http_server_request_duration_seconds_count{job="train-ticket-applications",http_response_status_code=~"5.."}[5m])
            )
            /
            clamp_min(
              sum by (namespace, service) (
                rate(http_server_request_duration_seconds_count{job="train-ticket-applications"}[5m])
              ), 0.000001
            )
          )
          or on (namespace, service)
          0 * sum by (namespace, service) (
            rate(http_server_request_duration_seconds_count{job="train-ticket-applications"}[5m])
          )
      - record: train_ticket:http_request_duration:p95_seconds5m
        expr: |
          histogram_quantile(0.95,
            sum by (namespace, service, http_route, le) (
              rate(http_server_request_duration_seconds_bucket{job="train-ticket-applications"}[5m])
            )
          )
      - record: train_ticket:http_request_duration:p99_seconds5m
        expr: |
          histogram_quantile(0.99,
            sum by (namespace, service, http_route, le) (
              rate(http_server_request_duration_seconds_bucket{job="train-ticket-applications"}[5m])
            )
          )
      - record: train_ticket:db_pool_connections_used:sum
        expr: |
          sum by (namespace, service) (
            db_client_connection_count{job="train-ticket-applications",db_client_connection_state="used"}
          )
          or
          sum by (namespace, service) (
            hikaricp_connections_active{job="train-ticket-applications"}
          )
      - record: train_ticket:db_pool_connections_max:sum
        expr: |
          sum by (namespace, service) (
            db_client_connection_max{job="train-ticket-applications"}
          )
          or
          sum by (namespace, service) (
            hikaricp_connections_max{job="train-ticket-applications"}
          )
      - record: train_ticket:db_pool_utilization:ratio
        expr: |
          train_ticket:db_pool_connections_used:sum
          /
          clamp_min(train_ticket:db_pool_connections_max:sum, 1)
      - record: train_ticket:db_pool_pending_requests:max
        expr: |
          max by (namespace, service) (
            db_client_connection_pending_requests{job="train-ticket-applications"}
          )
          or
          max by (namespace, service) (
            hikaricp_connections_pending{job="train-ticket-applications"}
          )

  - name: train-ticket-transport
    interval: 30s
    rules:
      - record: train_ticket:event_consumer_messages:rate5m
        expr: sum by (namespace, service, outcome) (rate(train_ticket_event_consumer_messages_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:event_consumer_failures:rate5m
        expr: sum by (namespace, service, reason_code) (rate(train_ticket_event_consumer_failures_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:event_consumer_dead_letters:increase5m
        expr: sum by (namespace, service, reason_code) (increase(train_ticket_event_consumer_dead_letters_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:outbox_unpublished_sample:max
        expr: max by (namespace, service) (train_ticket_outbox_unpublished_sample{job="train-ticket-applications"})
      - record: train_ticket:outbox_oldest_unpublished_age:max_seconds
        expr: max by (namespace, service) (train_ticket_outbox_oldest_unpublished_age_seconds{job="train-ticket-applications"})
      - record: train_ticket:outbox_published:rate5m
        expr: sum by (namespace, service) (rate(train_ticket_outbox_published_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:outbox_failures:rate5m
        expr: sum by (namespace, service, stage) (rate(train_ticket_outbox_failures_total{job="train-ticket-applications"}[5m]))

  - name: train-ticket-business
    interval: 30s
    rules:
      - record: train_ticket:order_funnel_transitions:rate5m
        expr: sum by (namespace, service, stage, outcome, channel) (rate(train_ticket_order_transitions_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:booking_saga_active:sum
        expr: sum by (namespace, service, stage) (train_ticket_booking_saga_active{job="train-ticket-applications"})
      - record: train_ticket:booking_saga_transitions:rate5m
        expr: sum by (namespace, service, stage, outcome, reason_code) (rate(train_ticket_booking_saga_transitions_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:booking_saga_oldest_age:max_seconds
        expr: max by (namespace, service, stage) (train_ticket_booking_saga_oldest_age_seconds{job="train-ticket-applications"})
      - record: train_ticket:payment_operations:rate5m
        expr: sum by (namespace, service, operation, outcome, currency, channel) (rate(train_ticket_payment_operations_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:payment_amount:increase5m_minor_units
        expr: sum by (namespace, service, operation, outcome, currency, channel) (increase(train_ticket_payment_amount_minor_units_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:payment_conservation:sum_minor_units
        expr: sum by (namespace, service, state, currency) (train_ticket_payment_conservation_minor_units{job="train-ticket-applications"})
      - record: train_ticket:capacity_units:sum
        expr: sum by (namespace, service, state, travel_class, seat_mode) (train_ticket_capacity_units{job="train-ticket-applications"})
      - record: train_ticket:capacity_hold_oldest_age:max_seconds
        expr: max by (namespace, service, state, travel_class) (train_ticket_capacity_hold_oldest_age_seconds{job="train-ticket-applications"})
      - record: train_ticket:seat_allocations:sum
        expr: sum by (namespace, service, state, travel_class, seat_mode) (train_ticket_seat_allocations{job="train-ticket-applications"})
      - record: train_ticket:ticketing_transitions:rate5m
        expr: sum by (namespace, service, stage, outcome, ticket_type, reason_code) (rate(train_ticket_ticketing_transitions_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:ticketing_active:sum
        expr: sum by (namespace, service, stage, ticket_type) (train_ticket_ticketing_active{job="train-ticket-applications"})
      - record: train_ticket:waitlist_intents:sum
        expr: sum by (namespace, service, state, travel_class) (train_ticket_waitlist_intents{job="train-ticket-applications"})
      - record: train_ticket:waitlist_oldest_age:max_seconds
        expr: max by (namespace, service, state, travel_class) (train_ticket_waitlist_oldest_age_seconds{job="train-ticket-applications"})
      - record: train_ticket:waitlist_promotions:rate5m
        expr: sum by (namespace, service, outcome, reason_code, travel_class) (rate(train_ticket_waitlist_promotions_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:wallet_balance:sum_minor_units
        expr: sum by (namespace, service, state, currency) (train_ticket_wallet_balance_minor_units{job="train-ticket-applications"})
      - record: train_ticket:wallet_operations:rate5m
        expr: sum by (namespace, service, operation, outcome, reason_code, currency) (rate(train_ticket_wallet_operations_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:notification_queue_tasks:sum
        expr: sum by (namespace, service, state, channel) (train_ticket_notification_queue_tasks{job="train-ticket-applications"})
      - record: train_ticket:notification_queue_oldest_age:max_seconds
        expr: max by (namespace, service, state, channel) (train_ticket_notification_queue_oldest_age_seconds{job="train-ticket-applications"})
      - record: train_ticket:notification_retries:rate5m
        expr: sum by (namespace, service, channel, outcome, reason_code) (rate(train_ticket_notification_retries_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:notification_policy_actions:rate5m
        expr: sum by (namespace, service, action, channel, outcome, reason_code) (rate(train_ticket_notification_policy_actions_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:notification_delivery_results:rate5m
        expr: sum by (namespace, service, channel, outcome, reason_code) (rate(train_ticket_notification_delivery_results_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:disruption_segment_signals:rate5m
        expr: sum by (namespace, service, signal_type, outcome, source) (rate(train_ticket_disruption_segment_signals_total{job="train-ticket-applications"}[5m]))
      - record: train_ticket:event_consumer_lag:max
        expr: max by (namespace, service, event_stream, consumer_group) (train_ticket_event_consumer_lag{job="train-ticket-applications"})
      - record: train_ticket:event_consumer_pending:max
        expr: max by (namespace, service, event_stream, consumer_group) (train_ticket_event_consumer_pending{job="train-ticket-applications"})
      - record: train_ticket:event_consumer_oldest_age:max_seconds
        expr: max by (namespace, service, event_stream, consumer_group) (train_ticket_event_consumer_oldest_age_seconds{job="train-ticket-applications"})
      - record: train_ticket:event_dlq_entries:max
        expr: max by (namespace, service, event_stream, consumer_group, event_type) (train_ticket_event_dlq_entries{job="train-ticket-applications"})
{{- end -}}
