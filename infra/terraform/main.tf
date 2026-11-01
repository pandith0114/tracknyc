# TrackNYC deployment skeleton. NOT APPLIED, NOT COMPLETE — see README.md in this directory.
#
# No provider is declared on purpose: the choice is yours, and declaring one here would imply
# this module works. The variables and outputs below sketch the interface a real module should
# have; the resources are commented out with notes on what each one must do.

terraform {
  required_version = ">= 1.6"
  # required_providers { ... }   # add your provider here
  # backend "s3" { ... }         # configure remote state before applying anywhere real
}

variable "environment" {
  description = "Deployment name, used in resource names and tags."
  type        = string
  default     = "prod"
}

variable "domain" {
  description = "Public hostname for the map, e.g. trains.example.org."
  type        = string
}

variable "mode" {
  description = "replay (recorded fixtures) or live (poll the real MTA feeds)."
  type        = string
  default     = "live"

  validation {
    condition     = contains(["replay", "live"], var.mode)
    error_message = "mode must be 'replay' or 'live'."
  }
}

variable "instance_size" {
  description = "Provider-specific size for the VM running the stack (2 vCPU / 4 GB is enough)."
  type        = string
  default     = "small"
}

variable "mta_api_key_secret_id" {
  description = "Secret-manager id holding MTA_API_KEY, if your account requires one. Never a literal key."
  type        = string
  default     = ""
  sensitive   = true
}

# --- What to create -----------------------------------------------------------------------
#
# resource "<provider>_instance" "service" {
#   # Runs infra/docker-compose.yml. Needs outbound HTTPS to the MTA endpoints and the tile
#   # server; inbound only 80/443 from the load balancer.
# }
#
# resource "<provider>_postgres" "db" {
#   # PostgreSQL 16 with the PostGIS extension available. Verify the provider offers PostGIS
#   # before choosing managed Postgres; the schema will not migrate without it.
# }
#
# resource "<provider>_redis" "cache" {
#   # Redis 7. Pub/Sub only; no persistence required. Sized for the latest state of a few
#   # thousand trains, so the smallest tier is plenty.
# }
#
# resource "<provider>_dns_record" "map" {
#   # var.domain -> the load balancer. HTTP/2 must be enabled: SSE over HTTP/1.1 runs into the
#   # browser's six-connections-per-origin limit.
# }

output "url" {
  description = "Where the map will be served once this module is finished."
  value       = "https://${var.domain}"
}

output "next_steps" {
  description = "Reminder that this module does not deploy anything yet."
  value       = "Skeleton only — see infra/terraform/README.md for what is left to fill in."
}
