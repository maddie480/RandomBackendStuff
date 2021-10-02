# Support Server Bot

This bot is here to help manage my bot support server. It is private and does the following things:
- Create a channel when a user clicks the "Create Channel" button in #welcome. The channel is created as private, with only the Mod Structure Verifier and the user having access. It then configures Mod Structure Verifier as if `--setup-no-names` and `--setup-free-names` were called, to allow verifying mods, and a welcome message is posted.
  - Each user is isolated in a channel, because... I don't really want to moderate a public server :sweat_smile: This way of working allows people to test out the bots and ask me questions, without talking between each other or having to deal with a #general channel.
- Auto-delete channels that have been inactive for more than 7 days, for cleanup purposes.
