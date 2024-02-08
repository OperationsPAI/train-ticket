FROM java:8-jre

RUN apt-get install -y procps --force-yes

RUN /bin/cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone

ADD ./target/ts-basic-service-1.0.jar /app/
CMD ["java", "-Xmx200m",  "-jar", "/app/ts-basic-service-1.0.jar"]

EXPOSE 15680