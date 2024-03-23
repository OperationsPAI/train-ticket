FROM node

RUN /bin/cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone
WORKDIR /app
ADD . . 
RUN npm install

CMD [ "npm", "start" ]

EXPOSE 8080