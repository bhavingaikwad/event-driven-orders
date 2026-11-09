variable "region" {
  type    = string
  default = "us-east-2"
}

variable "my_ip_cidr" {
  type        = string
  description = "Your public IP as x.x.x.x/32. The only address allowed to reach the app ports."
  validation {
    condition     = can(regex("/32$", var.my_ip_cidr)) && var.my_ip_cidr != "0.0.0.0/0"
    error_message = "my_ip_cidr must be a single address ending in /32, never 0.0.0.0/0."
  }
}

variable "instance_type" {
  type    = string
  default = "m7i-flex.large"
}

variable "repo_url" {
  type    = string
  default = "https://github.com/bhavingaikwad/event-driven-orders.git"
}

variable "git_ref" {
  type    = string
  default = "main"
}

variable "auto_terminate_minutes" {
  type        = number
  default     = 240
  description = "The instance powers off, and therefore terminates, after this many minutes."
}

variable "ami_arch" {
  type        = string
  default     = "x86_64"
  description = "CPU architecture of the AMI: x86_64 or arm64. Must match instance_type."
}
