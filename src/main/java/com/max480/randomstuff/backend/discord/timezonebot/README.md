# Timezone Bot

This bot allows server members to grab _timezone roles_ on your server, using slash commands. Here is the list of commands:

-    `/detect-timezone` - gives a link to [a page](https://maddie480.ovh/discord-bots/timezone-bot/detect-timezone) to figure out your timezone
-    `/timezone [tz_name]` (for example `/timezone Europe/London`) - sets your timezone role. This specific format allows to update the role according to daylight saving, but "UTC+1" also works.
-    `/remove-timezone` - removes your timezone role
-    `/discord-timestamp [date_time]` - gives a [Discord timestamp](https://discord.com/developers/docs/reference#message-formatting-timestamp-styles), to tell a date/time to other people regardless of their timezone
-    `/time-for [member]` - gives the time it is now for another member of the server, if they have a timezone role
-    `/world-clock [place]` - gives the time it is in another place in the world (a city or a country)
-    `/list-timezones [visibility] [names]` - lists the timezones of all members in the server that have timezone roles. You can pass `visibility = public` in order to have the bot response be visible to everyone in the channel.

Two more commands allow admins to set the bot up, and are only accessible to members with the "Administrator" or "Manage Server" permission:

-    `/toggle-times` - sets whether timezone roles should show the time it is in the timezone (for example `Timezone UTC+01:00 (2pm)`) or not (for example `Timezone UTC+01:00`). Enabling this causes "role update" events to be logged hourly. This is disabled by default.
-    `/timezone-dropdown` - creates a dropdown that lets users pick a timezone role. This is useful if most members in your server have the same timezone roles. An admin can set this up in a fixed `#roles` channel, similarly to reaction roles. [Check this page for help with the syntax and examples.](https://maddie480.ovh/discord-bots/timezone-bot/timezone-dropdown-help)

These slash commands issue private responses, so they can be used from anywhere without cluttering a channel with commands.

This bot **requires the Manage Roles permission** as it creates, deletes and updates timezone roles itself as needed. No other permission is required.

[Invite to your server](https://discord.com/oauth2/authorize?client_id=806514800045064213&scope=bot%20applications.commands&permissions=268435456)

_Note that this is the source code for the "with roles" variant of the Timezone Bot. The "without roles" variant does not require a bot user to work, and is part of [the maddie480.ovh website](https://github.com/maddie480/RandomStuffWebsite/tree/main/src/main/java/com/max480/randomstuff/gae/discord/timezonebot)._
