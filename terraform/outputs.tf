output "instance_id" {
  value = aws_instance.demo.id
}

output "public_ip" {
  value = aws_instance.demo.public_ip
}

output "order_service_url" {
  value = "http://${aws_instance.demo.public_ip}:8081"
}

output "inventory_service_url" {
  value = "http://${aws_instance.demo.public_ip}:8082"
}
