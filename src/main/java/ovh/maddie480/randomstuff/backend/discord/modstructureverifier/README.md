# Mod Structure Verifier

 This is a bot that downloads zips from Discord attachments or Google Drive links posted in a specific channel, and check if they are properly structured Celeste mods for [Everest](https://github.com/EverestAPI/Everest). This is useful to enforce a structure for collabs/contests, or just to check for some stuff like missing dependencies.

Server admins and moderators with the "Manage Server" permission can set up channels where other users can verify mods. The bot will ignore messages in other channels. 3 setups are available:

-    `--setup-fixed-names`: the admin setting up the bot chooses what the assets folder should be called, and all zips posted in the channel are checked with those settings. This setup is useful for servers dedicated to a collab/contest in particular.
-    `--setup-free-names`: the user can pick the assets folder name themselves by running the --verify command in the dedicated channel with the folder name as a parameter, and the zip / Google Drive link attached. This is useful on servers where people organize their own collabs/contests, since they can tell participants the command to run, without needing an admin to set up the bot.
-    `--setup-no-name`: the bot won't check folder names at all, and will only check if the everest.yaml is valid and missing entities, triggers, effects, stylegrounds and decals. This is useful to allow people to check their own mods.

You can combine `--setup-free-names` with one of the 2 other setups in the same channel: the bot will pick depending on whether the user uses `--verify` or not.

After setting the bot up, the users will be able to have their zips checked by just dropping the zip or the Google Drive link in the channel you designated (they will have to use the `--verify` command if you set up the bot with `--setup-free-names`).

[Invite to your server](https://discord.com/oauth2/authorize?client_id=809572233953542154&scope=bot&permissions=19520)
