# Random Discord Bots + Backend Stuff

This repository contains source code for several things that run on my backend. This includes two bots (**Mod Structure Verifier** and **Timezone Bot**), and a few backend scheduled tasks related to Celeste.

Note that this repository only contains the Timezone Bot variant that has timezone roles, since this one actually has a bot user and needs to run on the backend. The variant without roles uses an interaction endpoint URL (HTTP POST requests), and is [part of the max480-random-stuff.appspot.com website](https://github.com/max4805/RandomStuffWebsite/tree/main/src/main/java/com/max480/randomstuff/gae/discord/timezonebot).

This is not structured as a proper Java project since this code is bundled as part of a bigger package, among a lot of private and personal stuff, and run as a single JAR on my server. This is open sourced in case someone wants to know how the backend works, or wants to reuse it. As such, the pom.xml has dependencies that are not needed for any of the code that was published here. (This allows me to keep them up-to-date as well with Dependabot.)
