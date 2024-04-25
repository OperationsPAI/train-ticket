FROM node

RUN /bin/cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone
WORKDIR /app
ADD . . 
RUN npm install
ENV OTEL_SERVICE_NAME "ts-ticket-office-service"
CMD [ "npm", "start" ]

EXPOSE 8080