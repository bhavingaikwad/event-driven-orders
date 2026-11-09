terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

# Credentials come from the environment (AWS_PROFILE), never from this repo.
provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project = "event-driven-orders"
      Purpose = "m16-demo"
    }
  }
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-${var.ami_arch}"]
  }
  filter {
    name   = "architecture"
    values = [var.ami_arch]
  }
}

# Only your own IP can reach the app ports. No SSH port at all (use SSM).
resource "aws_security_group" "demo" {
  name_prefix = "orders-demo-"
  description = "Orders demo: app ports from one IP only"
  vpc_id      = data.aws_vpc.default.id
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "app" {
  for_each          = toset(["8081", "8082", "8083"])
  security_group_id = aws_security_group.demo.id
  cidr_ipv4         = var.my_ip_cidr
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  ip_protocol       = "tcp"
}

# Outbound is needed for package downloads, git clone and the SSM agent.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.demo.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# Lets you run commands on the box through SSM instead of opening SSH.
resource "aws_iam_role" "ssm" {
  name_prefix = "orders-demo-ssm-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ssm.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ssm" {
  name_prefix = "orders-demo-"
  role        = aws_iam_role.ssm.name
}

resource "aws_instance" "demo" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.instance_type
  subnet_id                   = sort(data.aws_subnets.default.ids)[0]
  vpc_security_group_ids      = [aws_security_group.demo.id]
  iam_instance_profile        = aws_iam_instance_profile.ssm.name
  associate_public_ip_address = true

  # A shutdown (including the 4h timer) TERMINATES the instance instead of stopping it.
  instance_initiated_shutdown_behavior = "terminate"

  metadata_options {
    http_tokens   = "required"
    http_endpoint = "enabled"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 30
    encrypted             = true
    delete_on_termination = true
  }

  user_data = join("\n", [
    "#!/bin/bash",
    "REPO_URL='${var.repo_url}'",
    "GIT_REF='${var.git_ref}'",
    "AUTO_TERMINATE_MINUTES='${var.auto_terminate_minutes}'",
    file("${path.module}/user_data.sh"),
  ])
  user_data_replace_on_change = true

  tags = {
    Name = "orders-demo"
  }
}
