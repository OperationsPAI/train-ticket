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
Common labels.

Not currently used: the templates inline the two labels they actually need
(app.kubernetes.io/name and /part-of), because those two are what
deploy/k8s/*.yaml sets and what every selector in both paths matches on. Kept
because adding managed-by/chart labels to a Deployment is a
spec.selector-adjacent change that must be made deliberately, in one place.

Call with a dict carrying both the root context and the name:
  {{- include "train-ticket.labels" (dict "root" $ "name" $name) }}
*/}}
{{- define "train-ticket.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: train-ticket
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: train-ticket-{{ .root.Chart.Version }}
{{- end -}}
