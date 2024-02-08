#!/bin/bash

TT_ROOT=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

source "$TT_ROOT/utils.sh"

namespace="$1"

kubectl delete -f deployment/kubernetes-manifests/quickstart-k8s/yamls -n $namespace

helm ls -n $namespace | awk '{print $1}' | xargs helm uninstall -n $namespace

helm uninstall $rabbitmqRelease -n $namespace
helm uninstall $nacosRelease -n $namespace
helm uninstall $nacosDBRelease -n $namespace
helm uninstall tsdb -n $namespace

kubectl label namespace $namespace istio-injection= --overwrite

kubectl delete -f deployment/kubernetes-manifests/skywalking -n $namespace

kubectl get pvc -n ts  | awk '{print $1}' | xargs kubectl delete pvc  -n $namespace

kubectl get pv -n ts  | awk '{print $1}' | xargs kubectl delete pv  -n $namespace

