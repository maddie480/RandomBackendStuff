FROM eclipse-temurin:21-jre

ENV DEBIAN_FRONTEND=noninteractive \
  TZ=Europe/Paris \
  LANG="fr_FR.UTF-8" \
  LANGUAGE="fr_FR.UTF-8" \
  LC_ALL="fr_FR.UTF-8" \
  TERM=xterm

RUN echo "fr_FR.UTF-8 UTF-8" > /etc/locale.gen && \
  locale-gen && \
  ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && \
  echo $TZ > /etc/timezone && \
  apt-get update && \
  apt-get install -y software-properties-common && \
  add-apt-repository ppa:dotnet/backports && \
  apt-get update && \
  apt-get install dotnet-sdk-8.0 wine figlet ffmpeg python3 -y && \
  apt-get upgrade -y && \
  apt-get remove -y software-properties-common && \
  apt-get autoremove -y && \
  apt-get clean

USER ubuntu

COPY --chown=ubuntu:ubuntu target/run_bot.sh /app/run_bot.sh
COPY --chown=ubuntu:ubuntu target/random-stuff-backend-0.0.1-SNAPSHOT.jar /app/random-stuff-backend-0.0.1-SNAPSHOT.jar
COPY --chown=ubuntu:ubuntu target/java-libs /app/java-libs
COPY --chown=ubuntu:ubuntu static /app/static
COPY --chown=ubuntu:ubuntu games.json /app/static/games.json

RUN cd /app/static && \
  chmod -c u+x /app/run_bot.sh *.sh && \
  dotnet tool install -g ilspycmd && \
  wine cmd /c echo success

VOLUME /logs
VOLUME /backend
VOLUME /shared

WORKDIR /backend
CMD ["/bin/bash", "-c", "/app/run_bot.sh"]

