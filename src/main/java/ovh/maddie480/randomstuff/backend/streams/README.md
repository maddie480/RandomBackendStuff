# LNJ Twitch / YouTube Bot

A small Twitch and YouTube bot that listens to the chat of a specific channel, and that can respond to 2 commands:
* `!clip` - creates a clip of the 30 last seconds of the stream
* `!poll` - runs a poll, users can vote using keywords in the chat. For example, `!poll "Are you bald?" Yes No "I'm not sure"`. Results are published on [https://maddie480.ovh/twitch-poll](https://maddie480.ovh/twitch-poll) and are refreshed every 5 seconds, so they can be shown on stream.

Also comes with [SHS Chat Control](features/SHSChatControl.java), the server side of a chat control mod for an obscure game called
[Streatham Hill Stories](https://store.steampowered.com/app/1423980/Streatham_Hill_Stories/). The client part is
[there](https://github.com/maddie480/BazarLNJ/tree/main/StreathamHillStories) (in French)!
