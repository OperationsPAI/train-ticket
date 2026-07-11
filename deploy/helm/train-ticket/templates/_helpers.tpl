{{/*
Full image path: registry/org/name:tag
*/}}
{{- define "train-ticket.image" -}}
{{- $registry := .global.imageRegistry -}}
{{- $org := .global.imageOrg -}}
{{- $tag := .global.imageTag -}}
{{- if $registry -}}
{{ $registry }}/{{ $org }}/{{ .name }}:{{ $tag }}
{{- else -}}
{{ $org }}/{{ .name }}:{{ $tag }}
{{- end -}}
{{- end -}}

{{/*
Infra image path: registry/repo:tag
*/}}
{{- define "train-ticket.infraImage" -}}
{{- $registry := .global.imageRegistry -}}
{{- if $registry -}}
{{- if contains "/" .repo -}}
{{ $registry }}/{{ .repo }}:{{ .tag }}
{{- else -}}
{{ $registry }}/library/{{ .repo }}:{{ .tag }}
{{- end -}}
{{- else -}}
{{ .repo }}:{{ .tag }}
{{- end -}}
{{- end -}}

{{/*
Common labels
*/}}
{{- define "train-ticket.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: train-ticket
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: train-ticket-{{ .Chart.Version }}
{{- end -}}

{{/*
Database URL
*/}}
{{- define "train-ticket.databaseUrl" -}}
postgresql://{{ .Values.postgres.credentials.username }}:{{ .Values.postgres.credentials.password }}@postgres:5432/{{ .db }}
{{- end -}}

{{/*
Redis URL
*/}}
{{- define "train-ticket.redisUrl" -}}
redis://redis.{{ .Release.Namespace }}.svc.cluster.local:6379
{{- end -}}
