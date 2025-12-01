#!/usr/bin/env python3
"""
Train Ticket OpenAPI 文档合并工具

将多个服务的 OpenAPI 文档合并为一个统一的文档，
以便于生成 Python SDK。
"""

import json
import os
import sys
from pathlib import Path
from typing import Dict, Any, List


def load_openapi_file(filepath: str) -> Dict[str, Any]:
    """加载 OpenAPI JSON 文件"""
    with open(filepath, 'r', encoding='utf-8') as f:
        return json.load(f)


def merge_openapi_docs(docs: List[Dict[str, Any]], output_title: str = "Train Ticket API") -> Dict[str, Any]:
    """
    合并多个 OpenAPI 文档为一个统一文档
    
    Args:
        docs: OpenAPI 文档列表，每个元素是 (服务名, 文档内容) 的元组
        output_title: 输出文档的标题
    
    Returns:
        合并后的 OpenAPI 文档
    """
    merged = {
        "openapi": "3.0.1",
        "info": {
            "title": output_title,
            "description": "Train Ticket 微服务系统统一 API 文档\n\n"
                          "此文档包含所有微服务的 API 接口定义，可用于生成客户端 SDK。",
            "version": "1.0.0",
            "contact": {
                "name": "Train Ticket Team"
            }
        },
        "servers": [
            {
                "url": "http://localhost:18888",
                "description": "Gateway Service (开发环境)"
            }
        ],
        "tags": [],
        "paths": {},
        "components": {
            "schemas": {},
            "securitySchemes": {
                "bearerAuth": {
                    "type": "http",
                    "scheme": "bearer",
                    "bearerFormat": "JWT"
                }
            }
        }
    }
    
    seen_tags = set()
    seen_schemas = {}
    
    for service_name, doc in docs:
        # 提取服务前缀 (例如: ts-travel-service -> travel)
        service_prefix = service_name.replace("ts-", "").replace("-service", "").replace("-", "_")
        
        # 合并 tags
        if "tags" in doc:
            for tag in doc["tags"]:
                tag_name = tag.get("name", "")
                if tag_name and tag_name not in seen_tags:
                    seen_tags.add(tag_name)
                    # 添加服务来源信息
                    tag_copy = tag.copy()
                    tag_copy["description"] = f"[{service_name}] {tag.get('description', '')}"
                    merged["tags"].append(tag_copy)
        
        # 合并 paths
        if "paths" in doc:
            for path, methods in doc["paths"].items():
                if path not in merged["paths"]:
                    merged["paths"][path] = {}
                
                for method, operation in methods.items():
                    if method in merged["paths"][path]:
                        # 路径冲突，添加服务前缀
                        print(f"警告: 路径冲突 {method.upper()} {path}，来自 {service_name}")
                    
                    # 复制操作定义
                    op_copy = operation.copy()
                    
                    # 添加服务标识到 operationId
                    if "operationId" in op_copy:
                        op_copy["operationId"] = f"{service_prefix}_{op_copy['operationId']}"
                    
                    # 添加服务标签
                    if "tags" not in op_copy:
                        op_copy["tags"] = []
                    
                    merged["paths"][path][method] = op_copy
        
        # 合并 components/schemas
        if "components" in doc and "schemas" in doc["components"]:
            for schema_name, schema_def in doc["components"]["schemas"].items():
                if schema_name in seen_schemas:
                    # 检查是否相同
                    if json.dumps(seen_schemas[schema_name], sort_keys=True) != json.dumps(schema_def, sort_keys=True):
                        # Schema 冲突，使用服务前缀
                        new_name = f"{service_prefix}_{schema_name}"
                        merged["components"]["schemas"][new_name] = schema_def
                else:
                    seen_schemas[schema_name] = schema_def
                    merged["components"]["schemas"][schema_name] = schema_def
    
    # 按路径排序
    merged["paths"] = dict(sorted(merged["paths"].items()))
    merged["tags"] = sorted(merged["tags"], key=lambda x: x.get("name", ""))
    
    return merged


def main():
    if len(sys.argv) < 2:
        print("用法: python merge-openapi.py <docs_directory> [output_file]")
        print("")
        print("示例:")
        print("  python merge-openapi.py ./openapi-docs")
        print("  python merge-openapi.py ./openapi-docs ./train-ticket-api.json")
        sys.exit(1)
    
    docs_dir = Path(sys.argv[1])
    output_file = sys.argv[2] if len(sys.argv) > 2 else "train-ticket-openapi.json"
    
    if not docs_dir.exists():
        print(f"错误: 目录不存在: {docs_dir}")
        sys.exit(1)
    
    # 加载所有 OpenAPI 文档
    docs = []
    json_files = sorted(docs_dir.glob("ts-*.json"))
    
    if not json_files:
        print(f"错误: 在 {docs_dir} 中未找到 OpenAPI 文档")
        sys.exit(1)
    
    print(f"正在加载 {len(json_files)} 个 OpenAPI 文档...")
    
    for json_file in json_files:
        try:
            doc = load_openapi_file(str(json_file))
            service_name = json_file.stem  # 文件名（不含扩展名）
            docs.append((service_name, doc))
            print(f"  ✓ {service_name}")
        except Exception as e:
            print(f"  ✗ {json_file.name}: {e}")
    
    if not docs:
        print("错误: 没有成功加载任何文档")
        sys.exit(1)
    
    # 合并文档
    print(f"\n正在合并 {len(docs)} 个文档...")
    merged = merge_openapi_docs(docs)
    
    # 统计信息
    path_count = len(merged["paths"])
    schema_count = len(merged["components"]["schemas"])
    
    # 保存合并后的文档
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(merged, f, indent=2, ensure_ascii=False)
    
    print(f"\n=== 合并完成 ===")
    print(f"API 路径数量: {path_count}")
    print(f"Schema 数量: {schema_count}")
    print(f"输出文件: {output_file}")
    
    # 生成 YAML 版本（可选）
    try:
        import yaml
        yaml_file = output_file.replace('.json', '.yaml')
        with open(yaml_file, 'w', encoding='utf-8') as f:
            yaml.dump(merged, f, allow_unicode=True, default_flow_style=False, sort_keys=False)
        print(f"YAML 版本: {yaml_file}")
    except ImportError:
        pass


if __name__ == "__main__":
    main()
