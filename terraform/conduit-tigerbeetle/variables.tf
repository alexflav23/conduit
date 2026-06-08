variable "cold_start" {
  type        = bool
  default     = false
  description = <<-EOT
    Set true to cold-start the cluster; in particular, some keys in S3
    will not be verified.
  EOT
}
