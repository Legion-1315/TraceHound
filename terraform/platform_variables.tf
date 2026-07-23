variable "infra_sa_email" {
  description = "The email address representation of the SA you can use to deploy infrastructure. It has the same access rights as human team members."
  type = string
}

variable "infra_sa_id" {
  description = "The fully qualified ID representation of the SA you can use to deploy infrastructure."
  type = string
}

variable "openshift_namespace" {
  description = "The OpenShift namespace for your team"
  type = string
}

variable "openshift_registry" {
  description = "The OpenShift internal registry URL"
  type = string
}

variable "openshift_server" {
  description = "The OpenShift cluster API endpoint URL"
  type = string
}

variable "project_id" {
  description = "Your team's GCP Project ID."
  type = string
}

variable "project_number" {
  description = "Your teams' GCP Project Number."
  type = string
}

variable "workload_identity_provider" {
  description = "The ID of the Workload Identity provider you cah use to authenticate from GitHub Actions to your GCP project."
  type = string
}

variable "workload_sa_email" {
  description = "The email address representation of the SA you can attach to your workloads (e.g. to a Cloud Run service). "
  type = string
}

variable "workload_sa_id" {
  description = "The fully qualified ID representation of the SA you can attach to your workloads (e.g. to a Cloud Run service). "
  type = string
}
