helm install originx originx/originx \
              --set global.skywalkingaddress="ts-skywalking-helm-oap.ts.svc.cluster.local" \
              --set global.portalp2p.username="lincyaw" \
              --set global.portalp2p.password="SZj6c2cUVTzqaAX" \
              --set global.originxversion="v0.1.0" \
              -n originx --create-namespace