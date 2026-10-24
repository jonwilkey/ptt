data "vultr_os" "debian" {
  filter {
    name   = "name"
    values = [var.os_name]
  }
}

# ---- Firewall ------------------------------------------------------------------------------
# Vultr firewall groups drop all inbound traffic that no rule allows. Only HTTP (ACME and the
# redirect to HTTPS) and HTTPS are open, on IPv4 and IPv6. There is deliberately no rule for
# port 22: SSH is reachable only over Tailscale, which needs no inbound port.

locals {
  firewall_rules = {
    for r in setproduct(["v4", "v6"], ["80", "443"]) : "${r[0]}-tcp-${r[1]}" => {
      ip_type = r[0]
      port    = r[1]
    }
  }
}

resource "vultr_firewall_group" "ptt" {
  description = "${var.hostname}: inbound TCP 80/443 only"
}

resource "vultr_firewall_rule" "public" {
  for_each = local.firewall_rules

  firewall_group_id = vultr_firewall_group.ptt.id
  protocol          = "tcp"
  ip_type           = each.value.ip_type
  subnet            = each.value.ip_type == "v4" ? "0.0.0.0" : "::"
  subnet_size       = 0
  port              = each.value.port
  notes             = "public ${each.value.port == "80" ? "HTTP (ACME, redirect)" : "HTTPS"}"
}

# ---- Reserved IPv4 -------------------------------------------------------------------------
# Kept across instance rebuilds so the A record never changes. Used as the instance's main IP
# (reserved_ip_id below), so the OS configures it automatically. prevent_destroy guards against
# losing the address by accident.
#
# IPv6 is not reserved (docs/adr/0004-ipv6-not-reserved.md): Vultr allows one reserved IP as
# the main IP, and an extra reserved /64 would need manual network configuration in the OS. The
# AAAA record follows the instance's own IPv6 address instead and OpenTofu updates it on a
# rebuild (TTL 300 s).

resource "vultr_reserved_ip" "v4" {
  region  = var.region
  ip_type = "v4"
  label   = "${var.hostname}-v4"

  lifecycle {
    prevent_destroy = true
  }
}

# ---- Instance ------------------------------------------------------------------------------

resource "vultr_instance" "ptt" {
  region   = var.region
  plan     = var.plan
  os_id    = data.vultr_os.debian.id
  hostname = var.hostname
  label    = var.hostname
  tags     = var.tags

  enable_ipv6       = true
  reserved_ip_id    = vultr_reserved_ip.v4.id
  firewall_group_id = vultr_firewall_group.ptt.id
  ddos_protection   = false
  activation_email  = false

  # Daily whole-disk backups: the only off-host copy of the server (see spec, Backups).
  backups = "enabled"
  backups_schedule {
    type = "daily"
    hour = var.backup_hour_utc
  }

  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    hostname           = var.hostname
    users_json         = jsonencode(local.cloud_init_users)
    tailscale_auth_key = var.tailscale_auth_key
  })

  lifecycle {
    # user_data is first-boot only. Ignoring it means rotating the Tailscale key or editing
    # admin_users never rebuilds the server; Ansible manages users and SSH after first boot.
    ignore_changes = [user_data]
  }
}

locals {
  cloud_init_users = [
    for u in var.admin_users : {
      name                = u.name
      groups              = ["sudo"]
      shell               = "/bin/bash"
      lock_passwd         = true
      sudo                = "ALL=(ALL) NOPASSWD:ALL"
      ssh_authorized_keys = u.ssh_public_keys
    }
  ]
}
