#!/usr/bin/env bash
# 从 Kubernetes 集群获取所有服务的 OpenAPI 文档

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_ROOT/openapi-docs"
NAMESPACE="${NAMESPACE:-default}"

SERVICES=(
    "ts-verification-code-service"
    "ts-contacts-service"
    "ts-order-service"
    "ts-order-other-service"
    "ts-config-service"
    "ts-station-service"
    "ts-train-service"
    "ts-travel-service"
    "ts-travel2-service"
    "ts-preserve-service"
    "ts-preserve-other-service"
    "ts-basic-service"
    "ts-price-service"
    "ts-notification-service"
    "ts-security-service"
    "ts-inside-payment-service"
    "ts-execute-service"
    "ts-payment-service"
    "ts-rebook-service"
    "ts-cancel-service"
    "ts-route-service"
    "ts-assurance-service"
    "ts-seat-service"
    "ts-travel-plan-service"
    "ts-route-plan-service"
    "ts-food-service"
    "ts-station-food-service"
    "ts-consign-price-service"
    "ts-consign-service"
    "ts-admin-order-service"
    "ts-admin-basic-info-service"
    "ts-admin-route-service"
    "ts-admin-travel-service"
    "ts-admin-user-service"
    "ts-auth-service"
    "ts-user-service"
    "ts-delivery-service"
    "ts-train-food-service"
    "ts-food-delivery-service"
    "ts-wait-order-service"
)

echo "=== Train Ticket OpenAPI 文档生成 (Kubernetes) ==="
echo "命名空间: $NAMESPACE"
echo ""

mkdir -p "$OUTPUT_DIR"

SUCCESS_COUNT=0
FAIL_COUNT=0

for service in "${SERVICES[@]}"; do
    echo -n "获取 $service... "
    
    # 使用 kubectl exec 直接在 pod 内部 curl
    POD=$(kubectl get pods -n "$NAMESPACE" -l app="$service" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
    
    if [ -z "$POD" ]; then
        echo "✗ Pod 未找到"
        ((FAIL_COUNT++)) || true
        continue
    fi
    
    if kubectl exec -n "$NAMESPACE" "$POD" -- curl -s --max-time 10 http://localhost:8080/v3/api-docs > "$OUTPUT_DIR/${service}.json" 2>/dev/null; then
        if [ -s "$OUTPUT_DIR/${service}.json" ] && jq empty "$OUTPUT_DIR/${service}.json" 2>/dev/null; then
            echo "✓"
            ((SUCCESS_COUNT++)) || true
        else
            rm -f "$OUTPUT_DIR/${service}.json"
            echo "✗ 无效的 JSON"
            ((FAIL_COUNT++)) || true
        fi
    else
        rm -f "$OUTPUT_DIR/${service}.json"
        echo "✗ 请求失败"
        ((FAIL_COUNT++)) || true
    fi
done

echo ""
echo "=== 完成 ==="
echo "成功: $SUCCESS_COUNT, 失败: $FAIL_COUNT"
echo "文档目录: $OUTPUT_DIR/"

# 合并文档
if [ $SUCCESS_COUNT -gt 0 ]; then
    echo ""
    echo "正在合并文档..."
    python3 "$SCRIPT_DIR/merge-openapi.py" "$OUTPUT_DIR" "$OUTPUT_DIR/train-ticket-openapi.json"
    echo ""
    echo "=========================================="
    echo "合并后的 OpenAPI 文档: $OUTPUT_DIR/train-ticket-openapi.json"
    echo ""
    echo "生成 Python SDK:"
    echo "  pip install openapi-python-client"
    echo "  openapi-python-client generate --path $OUTPUT_DIR/train-ticket-openapi.json"
    echo "=========================================="
fi
