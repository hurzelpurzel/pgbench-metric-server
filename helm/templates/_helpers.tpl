{{/*
Expand the name of the chart.
*/}}
{{- define "pgbench-metric-server.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name. The release name is used when it
already contains the chart name, so "helm install pgbench-metric-server ."
does not produce "pgbench-metric-server-pgbench-metric-server".
*/}}
{{- define "pgbench-metric-server.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart label, versioned and safe for label values.
*/}}
{{- define "pgbench-metric-server.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Labels shared by every resource of the chart.
*/}}
{{- define "pgbench-metric-server.labels" -}}
helm.sh/chart: {{ include "pgbench-metric-server.chart" . }}
{{ include "pgbench-metric-server.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: pgbench-metric-server
{{- end }}

{{/*
Selector labels. These are immutable in the Deployment and must not be changed
by user supplied labels.
*/}}
{{- define "pgbench-metric-server.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pgbench-metric-server.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Name of the ServiceAccount to use.
*/}}
{{- define "pgbench-metric-server.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "pgbench-metric-server.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Name of the Secret that carries the database password: either the user supplied
one or the one the chart creates from .Values.pgbench.connection.password.
*/}}
{{- define "pgbench-metric-server.passwordSecretName" -}}
{{- if .Values.pgbench.connection.existingSecret }}
{{- .Values.pgbench.connection.existingSecret }}
{{- else }}
{{- include "pgbench-metric-server.fullname" . }}
{{- end }}
{{- end }}
