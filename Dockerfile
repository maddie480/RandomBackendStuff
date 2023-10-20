FROM eclipse-temurin:21-jre

ENV DEBIAN_FRONTEND=noninteractive TERM=xterm

RUN apt-get update && \
  apt-get install dotnet-sdk-6.0 wine -y && \
  apt-get upgrade -y && \
  apt-get clean && \
  groupadd -g 1000 debian && \
  useradd -u 1000 -g debian debian && \
  mkdir /home/debian && \
  chown -c debian:debian /home/debian

USER debian

COPY --chown=debian:debian target/run_bot.sh /app/run_bot.sh
COPY --chown=debian:debian target/random-stuff-backend-0.0.1-SNAPSHOT.jar /app/random-stuff-backend-0.0.1-SNAPSHOT.jar
COPY --chown=debian:debian target/java-libs /app/java-libs
COPY --chown=debian:debian static /app/static

RUN cd /app/static && \
  chmod -c u+x /app/run_bot.sh *.sh && \
  dotnet tool install -g ilspycmd && \
  wine cmd /c echo success

VOLUME /logs
VOLUME /backend
VOLUME /shared

WORKDIR /backend
CMD ["/bin/bash", "-c", "/app/run_bot.sh"]
