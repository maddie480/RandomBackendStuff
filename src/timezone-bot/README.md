# Timezone Bot

This bot allows server members to grab _timezone roles_ on your server, using slash commands. Here is the list of commands:

-    `/detect_timezone` - gives a link to [a page](https://max480-random-stuff.appspot.com/detect-timezone.html) to figure out your timezone
-    `/timezone [tz]` (for example `/timezone Europe/London`) - sets your timezone role. This specific format allows to update the role according to daylight saving, but "UTC+1" also works.
-    `/remove_timezone` - removes your timezone role
-    `/toggle_times` - sets whether timezone roles should show the time it is in the timezone (for example `Timezone UTC+01:00 (2pm)`) or not (for example `Timezone UTC+01:00`). Enabling this causes "role update" events to be logged hourly. This is disabled by default, and only members with the Manage Server or Admin permission can enable it.

These slash commands issue private responses, so they can be used from anywhere without cluttering a channel with commands.

This bot **requires the Manage Roles permission** as it creates, deletes and updates timezone roles itself as needed. No other permission is required.

Example usage:

![usage](https://max480-random-stuff.appspot.com/img/example_timezone_bot.png)

The timezone role will look like this if showing the time it is in the timezone is enabled:

![timezone role with times](https://max480-random-stuff.appspot.com/img/timezone_role_with_times.png)

and it will look like this if it is disabled:

![timezone role without times](https://max480-random-stuff.appspot.com/img/timezone_role_no_time.png)

[Invite to your server](https://discord.com/oauth2/authorize?client_id=806514800045064213&scope=bot%20applications.commands&permissions=268435456)
