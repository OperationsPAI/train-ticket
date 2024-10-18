FROM caddy:2.8.4

# RUN /bin/cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone
# RUN apt-get update ; apt-get install unzip

# ADD https://github.com/open-telemetry/opentelemetry-cpp-contrib/releases/download/webserver%2Fv1.0.3/opentelemetry-webserver-sdk-x64-linux.tgz /opt

# RUN cd /opt ; unzip opentelemetry-webserver-sdk-x64-linux.tgz.zip; tar xvfz opentelemetry-webserver-sdk-x64-linux.tgz
# RUN cd /opt/opentelemetry-webserver-sdk; ./install.sh

# ENV LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/opt/opentelemetry-webserver-sdk/sdk_lib/lib
# RUN echo "load_module /opt/opentelemetry-webserver-sdk/WebServerModule/Nginx/1.23.1/ngx_http_opentelemetry_module.so;\n$(cat /etc/nginx/nginx.conf)" > /etc/nginx/nginx.conf

# COPY opentelemetry_module.conf /etc/nginx/conf.d
# COPY server.conf /etc/nginx/conf.d
COPY static /usr/share/nginx/html
COPY Caddyfile /etc/Caddyfile

RUN wget -O /tmp/caddy.gz "https://github.com/YifanYang6/caddy/releases/download/v0.0.0/caddy.gz" && \
    gunzip /tmp/caddy.gz && \
    mv /tmp/caddy /usr/bin/caddy && \
    chmod +x /usr/bin/caddy && \
    setcap cap_net_bind_service=+ep /usr/bin/caddy

CMD ["caddy", "run", "--config", "/etc/Caddyfile"]