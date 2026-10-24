output "reserved_ipv4" {
  description = "Reserved public IPv4 address (survives instance rebuilds); target of the A record."
  value       = vultr_reserved_ip.v4.subnet
}

output "ipv6" {
  description = "Instance's public IPv6 address; target of the AAAA record (changes on rebuild)."
  value       = vultr_instance.ptt.v6_main_ip
}

output "instance_id" {
  description = "Vultr instance id."
  value       = vultr_instance.ptt.id
}

output "fqdn" {
  description = "Public name of the server."
  value       = local.fqdn
}
