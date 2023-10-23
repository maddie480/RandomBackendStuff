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
  apt-get install dotnet-sdk-6.0 wine figlet ffmpeg python3 -y && \
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
  wine cmd /c echo success && \
  wget -O games.json "https://discordapp.com/api/v6/applications/detectable" && \
  curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o ./youtube-dl && \
  chmod -c a+rx ./youtube-dl

VOLUME /logs
VOLUME /backend
VOLUME /shared

WORKDIR /backend
CMD ["/bin/bash", "-c", "/app/run_bot.sh"]
