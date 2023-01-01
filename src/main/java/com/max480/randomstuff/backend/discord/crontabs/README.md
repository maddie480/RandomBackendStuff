# Discord Bots-related backend scheduled tasks

These classes have methods that are run periodically, and take part in the operations of [the Discord bots](https://max480-random-stuff.appspot.com/discord-bots).

The code for the bots is open-sourced here:
- [Timezone Bot (with roles)](https://github.com/max4805/RandomBackendStuff/tree/main/src/main/java/com/max480/randomstuff/backend/discord/timezonebot)
- [Timezone Bot (without roles)](https://github.com/max4805/RandomStuffWebsite/tree/main/src/main/java/com/max480/randomstuff/gae/discord/timezonebot)
- [Mod Structure Verifier](https://github.com/max4805/RandomBackendStuff/tree/main/src/main/java/com/max480/randomstuff/backend/discord/modstructureverifier)
- [Games Bot](https://github.com/max4805/RandomStuffWebsite/tree/main/src/main/java/com/max480/randomstuff/gae/discord/gamescommands)
- [Custom Slash Commands](https://github.com/max4805/RandomStuffWebsite/tree/main/src/main/java/com/max480/randomstuff/gae/discord/customslashcommands)

This folder contains some extra processes related to the Games Bot and the Custom Slash Commands integration, that run on the backend server (rather than on the frontend, like the bots themselves):
- `AutoLeaver`: makes Custom Slash Commands and Games Bot leave all servers they are in every day. Users do not need (and should not) invite the bot user to their server, as it is offline all the time; they only need to invite the slash commands. Forcing this will remove any risk of them being in too many guilds and requiring verification (even though the risk is near zero to start with :sweat_smile:).
- `CustomSlashCommandsCleanup`: this checks every day if the application is still present on all servers with registered custom slash commands, and deletes slash command information if this is not the case (since they do not exist on Discord's end anyway).
- `ServerCountUploader`: fetches server counts of each bot and uploads a file on Google Cloud Storage for display on [the Discord bots page](https://max480-random-stuff.appspot.com/discord-bots) on the website.
  - The Timezone Bot (with roles) and the Mod Structure Verifier just use the number of servers the bot user is part of
  - The Games Bot and Timezone Bot (without roles) use command history to figure out in how many servers they were used in the last 30 days ([command history is kept for no longer than that](https://max480-random-stuff.appspot.com/discord-bots/terms-and-privacy))
  - Custom Slash Commands uses the amount of servers that have custom slash commands configured. This runs after `CustomSlashCommandsCleanup`, so the count takes removed servers into account
- `TopGGCommunicator`: this class is in charge of communicating with [top.gg](https://top.gg/), where the Games Bot and Custom Slash Commands are referenced.
  - It uploads the server count for both bots, once a day, based on the results of `ServerCountUploader`
  - It notifies the owner of any votes or comments, in order to act on them in case there is a complaint / bug report left on the top.gg page
- `WorldClockHealthCheck`: checks daily that Timezone Bot commands relying on external APIs (`/world-clock` relies on [Nominatim from OpenStreetMap](https://nominatim.openstreetmap.org/) and [TimeZoneDB.com](https://timezonedb.com/), recognizing timezones like "CEST" relies on [timeanddate.com](https://www.timeanddate.com/time/zones/)) still work as expected.