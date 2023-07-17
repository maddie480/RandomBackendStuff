# Random Discord Bots + Backend Stuff

This repository contains source code for several things that run on my backend. This includes two bots (**Mod Structure Verifier** and **Timezone Bot**), and a few backend scheduled tasks related to Celeste.

Note that this repository only contains the Timezone Bot variant that has timezone roles, since this one actually has a bot user and needs to run on the backend. The variant without roles uses an interaction endpoint URL (HTTP POST requests), and is [part of the maddie480.ovh website](https://github.com/maddie480/RandomStuffWebsite/tree/main/src/main/java/com/max480/randomstuff/gae/discord/timezonebot).

The JAR built from this repository is used as a library, as this code is run among a lot of private and personal stuff. This is open sourced in case someone wants to know how the backend works, or wants to reuse it. As such, the pom.xml has dependencies that are not needed for any of the code that was published here. (This allows me to keep them up-to-date as well with Dependabot.)

## How the bots run

The [CrontabRunner](src/main/java/com/max480/randomstuff/backend/CrontabRunner.java) class is the one controlling the bots' execution:
- it is called without arguments in order to start up:
  - The Timezone Bot with roles
  - The Mod Structure Verifier bot
  - A health check bot
  - A socket server the frontend can use to trigger backend tasks
- for scheduled processes, it is called by a crontab with different arguments:
  - `--daily`: called every day at 16:15 GMT
  - `--hourly`: called every hour when the minute is 45
  - `--updater`: called every 2 minutes except when the minute is 0, or if another instance is running
  - `--updater-full`: called every hour when the minute is 0, except if another instance is running
