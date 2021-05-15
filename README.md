# Random Discord Bots

This repository contains source code for 2 bots I am currently hosting:
- **Timezone Bot**: a bot allowing people to assign timezone roles to themselves. The roles then get updated on an hourly basis to be able to tell what time it is for someone by looking at their roles.
- **Mod Structure Verifier**: a bot that downloads zips from Discord attachments or Google Drive links posted in a specific channel, and check if they are properly structured Celeste mods for [Everest](https://github.com/EverestAPI/Everest). This is useful to enforce a structure for collabs/contests.

This is not structured as a proper Java project since those bots are run as part of a bigger package, and running them requires some server setup. This is open sourced in case someone wants to know how the bots work, or wants to reuse them. As such, the pom.xml has dependencies that are not needed for any of the code that was published here. (This allows me to keep them up-to-date as well with Dependabot.)

This repository also contains code for a script that checks if one of the Celeste mods on GameBanana uses `yield return orig(self)`, as it can cause issues such as TAS desyncs. This is here because I'm packaging it with the bots, and invoking it from them.
