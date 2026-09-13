{{/*
Service image path: registry/org/name:tag

Two repository layouts are supported, selected by `global.imageRepo`:

  - unset (default): one repository PER service -- `org/name:tag`.
  - set: one repository for the WHOLE chart, with the service name folded into
    the tag -- `org/repo:<name>-<tag>`. Registries that bill or permission per
    repository (ACR, Harbor) make a per-service layout 39 things to create and
    publish; a single repository is one. It also means a pull secret or a
    public/private toggle only ever has to be applied once.

`name` is the service key, which is also the Deployment name, so the tag reads
as `<service>-<build>`, e.g. `journey-order-20260913`.
*/}}
{{- define "train-ticket.image" -}}
{{- $registry := .global.imageRegistry -}}
{{- $org := .global.imageOrg -}}
{{- $name := .name -}}
{{- $tag := .global.imageTag -}}
{{- if .global.imageRepo -}}
{{- $tag = printf "%s-%s" $name (toString $tag) -}}
{{- $name = .global.imageRepo -}}
{{- end -}}
{{- if $registry -}}
{{ $registry }}/{{ $org }}/{{ $name }}:{{ $tag }}
{{- else -}}
{{ $org }}/{{ $name }}:{{ $tag }}
{{- end -}}
{{- end -}}

{{/*
Infra image path: registry/repo:tag

`global.infraRegistry` overrides `global.imageRegistry` here, and it is read
with hasKey rather than `| default` so that an EXPLICIT empty string means
"docker.io" instead of silently falling back.

That distinction is load-bearing. The chart was written against a pull-through
mirror (pair-cn-guangzhou.cr.volces.com), where prefixing `library/` onto
`postgres` resolves because the mirror proxies docker.io. A plain registry does
not: pointing imageRegistry at ACR renders
`.../library/postgres:16-alpine`, a namespace that does not exist, and every
Postgres, Redis, busybox init container, Jaeger, OTel collector and Mailpit pod
goes ImagePullBackOff while the 39 service images pull fine.

WHEN `global.imageRepo` IS SET, infra images are mirrored into that same single
repository, tagged `<basename>-<tag>` (`postgres-16-alpine`,
`all-in-one-1.57`). That is not decoration: it is what lets a cluster with no
route to docker.io run at all. `admin:school` can reach a registry but times out
against `auth.docker.io`, so `curlimages/curl`, `jaegertracing/all-in-one` and
`axllent/mailpit` all fail to pull -- while `postgres:16-alpine` and
`redis:7-alpine` succeed only because those layers are already cached on the
nodes, which is a property of the nodes' history and not something a deploy can
rely on. Putting the upstream images in the same repository as the services
makes the release depend on exactly one reachable registry.

Setting `infraRegistry: ""` explicitly opts out and sends infra images back to
docker.io, for a cluster that can reach it and does not want them mirrored.
*/}}
{{- define "train-ticket.infraImage" -}}
{{- $registry := .global.imageRegistry -}}
{{- if hasKey .global "infraRegistry" -}}
{{- $registry = .global.infraRegistry -}}
{{- end -}}
{{- $repo := .repo -}}
{{- $tag := .tag -}}
{{- if and $registry .global.imageRepo -}}
{{- $tag = printf "%s-%s" (base $repo) (toString $tag) -}}
{{ $registry }}/{{ .global.imageOrg }}/{{ .global.imageRepo }}:{{ $tag }}
{{- else if $registry -}}
{{- if contains "/" $repo -}}
{{ $registry }}/{{ $repo }}:{{ $tag }}
{{- else -}}
{{ $registry }}/library/{{ $repo }}:{{ $tag }}
{{- end -}}
{{- else -}}
{{ .repo }}:{{ .tag }}
{{- end -}}
{{- end -}}

{{/*
Common labels.

Not currently used: the templates inline the two labels they actually need
(app.kubernetes.io/name and /part-of), because those two are what every
selector in this chart -- and every `kubectl get -l` in the deploy scripts and
e2e suite -- matches on. Kept because adding managed-by/chart labels to a
Deployment is a spec.selector-adjacent change that must be made deliberately,
in one place.

Call with a dict carrying both the root context and the name:
  {{- include "train-ticket.labels" (dict "root" $ "name" $name) }}
*/}}
{{- define "train-ticket.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: train-ticket
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: train-ticket-{{ .root.Chart.Version }}
{{- end -}}
