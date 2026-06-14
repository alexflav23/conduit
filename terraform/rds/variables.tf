variable "tunnel_local_port" {
  default = 0
  type    = number
}

variable "snapshot_identifier" {
  type    = string
  default = null
}

variable "skip_final_snapshot" {
  type    = bool
  default = false
}
