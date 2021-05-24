# Random Discord Bots

This repository contains source code for 2 bots I am currently hosting:
- **Timezone Bot**: a bot allowing people to assign timezone roles to themselves. The roles then get updated on an hourly basis to be able to tell what time it is for someone by looking at their roles.
- **Mod Structure Verifier**: a bot that downloads zips from Discord attachments or Google Drive links posted in a specific channel, and check if they are properly structured Celeste mods for [Everest](https://github.com/EverestAPI/Everest). This is useful to enforce a structure for collabs/contests.

This is not structured as a proper Java project since those bots are run as part of a bigger package, and running them requires some server setup. This is open sourced in case someone wants to know how the bots work, or wants to reuse them. As such, the pom.xml has dependencies that are not needed for any of the code that was published here. (This allows me to keep them up-to-date as well with Dependabot.)

This repository also contains a seemingly unrelated class called `GameBananaAutomatedChecks`, which is actually bundled with the bots in order to be run once a day, at midnight French time. This runs some checks on all GameBanana submissions to help with moderation and detection of common issues:
- code mods that use `yield return orig(self)` are reported, because using that in a hook causes 1 or 2-frame delays that are only noticed by people TASing the game.
- code mods that use `GetFunctionPointer()` to get a pointer to a function and "skip" some parent classes (for example calling `ParentOfParentClass.Method()` instead of `ParentClass.Method()`) are reported, because those cause crashes on Mac only and with no error log, making troubleshooting quite tedious.
- mods that ship with files included with Celeste or Everest are reported, because this is unnecessary at best... or illegal at worst (in the case of Celeste.exe).
- files that have the same everest.yaml as another file while being attached to a different mod are reported, because that probably means there is a mod name conflict.
 