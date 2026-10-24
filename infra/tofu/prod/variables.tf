# ---- Vultr instance ------------------------------------------------------------------------

variable "region" {
  description = "Vultr region id. \"sea\" is Seattle."
  type        = string
  default     = "sea"
}

variable "plan" {
  description = "Vultr plan id. vc2-1c-1gb is the smallest regular Cloud Compute plan offered in sea (1 vCPU, 1 GB RAM, 25 GB SSD, $5/month)."
  type        = string
  default     = "vc2-1c-1gb"
}

variable "os_name" {
  description = "Exact Vultr OS name to install (Debian stable), looked up with the vultr_os data source. List them with: curl -s https://api.vultr.com/v2/os | jq -r '.os[].name'"
  type        = string
  default     = "Debian 13 x64 (trixie)"
}

variable "hostname" {
  description = "Instance hostname and label, also used as the Tailscale machine name. Changing it replaces the instance."
  type        = string
  default     = "ptt-prod"

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", var.hostname))
    error_message = "hostname must be a single lower-case DNS label."
  }
}

variable "backup_hour_utc" {
  description = "UTC hour (0-23) for Vultr's daily automatic backup. 10 = 03:00 PDT / 02:00 PST: after the nightly 02:00 Pacific SQLite snapshot in summer, but the same hour as the snapshot in winter (PST), when a backup that starts at 02:00 may miss that night's snapshot and contain the previous one (the live WAL database is still crash-consistent). 11 would collide with the 04:00 PDT reboot window."
  type        = number
  default     = 10

  validation {
    condition     = var.backup_hour_utc >= 0 && var.backup_hour_utc <= 23 && floor(var.backup_hour_utc) == var.backup_hour_utc
    error_message = "backup_hour_utc must be an integer from 0 to 23."
  }
}

variable "tags" {
  description = "Tags applied to the Vultr instance."
  type        = set(string)
  default     = ["ptt", "prod", "managed-by-opentofu"]
}

# ---- First-boot configuration (cloud-init) -------------------------------------------------
# Used only when the instance is created; later changes are ignored (lifecycle.ignore_changes)
# and Ansible owns host configuration from then on.

variable "admin_users" {
  description = "Admin accounts created at first boot: Linux user name plus SSH public keys. Users get passwordless sudo and no password (key-only login, over Tailscale)."
  type = list(object({
    name            = string
    ssh_public_keys = list(string)
  }))
  # Not secret, but identifying (user names, key comments); keeps them out of public CI logs.
  sensitive = true

  validation {
    condition     = length(var.admin_users) > 0
    error_message = "At least one admin user is required."
  }

  validation {
    condition = alltrue([
      for u in var.admin_users : can(regex("^[a-z_][a-z0-9_-]{0,31}$", u.name)) && u.name != "root"
    ])
    error_message = "Each admin user name must be a valid lower-case Linux user name and not root."
  }

  validation {
    condition = alltrue([
      for u in var.admin_users : length(u.ssh_public_keys) > 0 && alltrue([
        for k in u.ssh_public_keys : can(regex("^(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com|ssh-rsa) [A-Za-z0-9+/=]+( .*)?$", k))
      ])
    ])
    error_message = "Each admin user needs at least one SSH public key in OpenSSH format."
  }
}

variable "tailscale_auth_key" {
  description = "One-time, pre-authorized Tailscale auth key tagged tag:ptt-server, used at first boot to join the tailnet. Needed only when the instance is created or replaced; leave empty otherwise. Without it a new server has no SSH access (Vultr web console only)."
  type        = string
  default     = ""
  sensitive   = true

  validation {
    condition     = var.tailscale_auth_key == "" || startswith(var.tailscale_auth_key, "tskey-auth-")
    error_message = "tailscale_auth_key must be empty or a Tailscale auth key (tskey-auth-...)."
  }
}

# ---- DNS -----------------------------------------------------------------------------------

# zone_name and record_name have no defaults on purpose: this repository is public, so real
# values live in a git-ignored terraform.tfvars locally and in GitHub Actions secrets in CI.

variable "zone_name" {
  description = "Existing public Route 53 hosted zone, read as a data source, e.g. example.com."
  type        = string
}

variable "record_name" {
  description = "Fully qualified name of the server's A/AAAA records inside zone_name, e.g. ptt.example.com. The CI apply role may change only this name (bootstrap's record_name), so changing it also needs a bootstrap change."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.record_name))
    error_message = "record_name must be a lower-case fully qualified domain name without a trailing dot."
  }
}

variable "dns_ttl" {
  description = "TTL in seconds for the A and AAAA records."
  type        = number
  default     = 300
}
