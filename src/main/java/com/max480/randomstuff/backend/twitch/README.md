# LNJ Twitch Bot

A small Twitch bot that listens to the chat of a specific channel, and that can respond to 2 commands:
* `!clip` - creates a clip of the 30 last seconds of the stream
* `!poll` - runs a poll, users can vote using keywords in the chat. For example, `!poll "Are you bald?" Yes No "I'm not sure"`. Results are published on [https://maddie480.ovh/twitch-poll](https://maddie480.ovh/twitch-poll) and are refreshed every 5 seconds, so they can be shown on stream.