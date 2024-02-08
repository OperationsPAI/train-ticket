FROM java:8-jre

RUN /bin/cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone

RUN apt-get install -y procps --force-yes

ADD ./target/ts-verification-code-service-1.0.jar /app/
CMD ["java", "-Xmx200m",  "-jar", "/app/ts-verification-code-service-1.0.jar"]

EXPOSE 15678/home/ygb/train-ticket/ts-news-service