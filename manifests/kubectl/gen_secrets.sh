#!/bin/bash

# 定义服务列表
svc_list="admin-basic-info admin-order admin-route admin-travel admin-user assurance auth avatar basic cancel common config consign-price consign contacts delivery execute food-delivery food gateway inside-payment news notification order-other order payment preserve-other preserve price rebook route-plan route seat security station-food station ticket-office train-food train travel-plan travel travel2 ui-dashboard user verification-code voucher wait-order"

# 定义生成的Secrets YAML文件的路径
secret_yaml_path="./secret.yaml"

# 为特定服务生成MySQL连接信息的Secret
function generate_mysql_secret_for_service {
  local service="$1"
  local host="$2"
  local user="$3"
  local password="$4"
  local database="$5"

  # 生成环境变量名
  local env_prefix=$(echo "${service}-mysql-" | tr '-' '_' | tr a-z A-Z)
  local env_host="${env_prefix}HOST"
  local env_port="${env_prefix}PORT"
  local env_database="${env_prefix}DATABASE"
  local env_user="${env_prefix}USER"
  local env_password="${env_prefix}PASSWORD"

  # 向YAML文件追加Secret定义
  cat >> $secret_yaml_path <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: ts-$service-service
type: Opaque
stringData:
  $env_host: "$host"
  $env_port: "3306"
  $env_database: "$database"
  $env_user: "$user"
  $env_password: "$password"
---
EOF
}

# 为所有服务生成MySQL连接信息的Secrets
function generate_mysql_secrets_for_all_services {
  local mysql_user="$1"
  local mysql_password="$2"
  local mysql_database="$3"
  local mysql_host=""
  local use_single_host=0

  # 如果提供了MySQL主机地址，则使用该地址
  if [ $# -eq 4 ]; then
    mysql_host="$4"
    use_single_host=1
  fi

  # 清理旧的Secrets文件并创建新文件
  rm -f $secret_yaml_path
  touch $secret_yaml_path

  # 为每个服务生成Secret
  for service in $svc_list
  do
    if [ $use_single_host -eq 0 ]; then
      mysql_host="ts-$service-mysql-leader"
    fi
    generate_mysql_secret_for_service $service $mysql_host $mysql_user $mysql_password $mysql_database
  done
}

# 示例调用
generate_mysql_secrets_for_all_services "root" "yourpassword" "ts" "mysql"
