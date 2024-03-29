
# install prometheus stack
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm install prometheus-stack prometheus-community/kube-prometheus-stack -n monitoring

# uninstall prometheus stack
helm uninstall prometheus-stack -n monitoring
kubectl delete crd alertmanagerconfigs.monitoring.coreos.com -n monitoring
kubectl delete crd alertmanagers.monitoring.coreos.com -n monitoring
kubectl delete crd podmonitors.monitoring.coreos.com -n monitoring
kubectl delete crd probes.monitoring.coreos.com -n monitoring
kubectl delete crd prometheusagents.monitoring.coreos.com -n monitoring
kubectl delete crd prometheuses.monitoring.coreos.com -n monitoring
kubectl delete crd prometheusrules.monitoring.coreos.com -n monitoring
kubectl delete crd scrapeconfigs.monitoring.coreos.com -n monitoring
kubectl delete crd servicemonitors.monitoring.coreos.com -n monitoring
kubectl delete crd thanosrulers.monitoring.coreos.com -n monitoring




# 安装 skywalking
export SKYWALKING_RELEASE_VERSION=4.5.0
export SKYWALKING_RELEASE_NAME=skywalking  # change the release name according to your scenario
export SKYWALKING_RELEASE_NAMESPACE=monitoring    # change the namespace to where you want to install SkyWalking

helm install "$SKYWALKING_RELEASE_NAME" \
  oci://registry-1.docker.io/apache/skywalking-helm \
  --version "$SKYWALKING_RELEASE_VERSION" \
  -n "$SKYWALKING_RELEASE_NAMESPACE" \
  -f skywalking_values.yaml



helm uninstall "$SKYWALKING_RELEASE_NAME"  -n "$SKYWALKING_RELEASE_NAMESPACE"



# 安装origin x
helm install originx originx/originx \
              --set global.skywalkingaddress="" \
              --set global.portalp2p.username="aoyangfang@link.cuhk.edu.cn" \
              --set global.portalp2p.password="SZj6c2cUVTzqaAX" \
              --set global.originxversion="v0.1.0" \
              -n originx --create-namespace


# 安装psql
helm install psql oci://registry-1.docker.io/bitnamicharts/postgresql -n monitoring -f psql.yaml