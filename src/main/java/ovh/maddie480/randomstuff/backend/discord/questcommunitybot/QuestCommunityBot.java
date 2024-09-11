package ovh.maddie480.randomstuff.backend.discord.questcommunitybot;

import com.google.common.collect.ImmutableMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.admin.PingCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.admin.ShutdownCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.admin.UptimeCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.gamestats.*;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling.*;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc.*;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.quest.*;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.random.*;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.reminders.AddReminderCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.reminders.ListRemindersCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.reminders.ReminderEngine;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.reminders.RemoveReminderCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.search.GoogleImageSearchCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.search.GoogleSearchCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.search.WikipediaCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.search.YouTubeSearchCommand;
import ovh.maddie480.randomstuff.backend.streams.features.CommandParser;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class QuestCommunityBot extends ListenerAdapter implements BotCommand {

    private static final Logger log = LoggerFactory.getLogger(QuestCommunityBot.class);

    private final Map<String, List<BotCommand>> commandCategories;
    private final PlagiatTatsumaki levelingManager;

    public QuestCommunityBot() throws IOException {
        JDA client;

        try {
            client = JDABuilder.create(SecretConstants.QUEST_COMMUNITY_BOT_TOKEN, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES,
                            GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGES,
                            GatewayIntent.DIRECT_MESSAGE_REACTIONS, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(this)
                    .build().awaitReady();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        Guild questGuild = Utils.getQuestGuild(client);

        // instantiate all the services
        GamestatsManager gamestatsManager = new GamestatsManager();
        gamestatsManager.run(questGuild);

        levelingManager = new PlagiatTatsumaki(gamestatsManager, questGuild);

        ReminderEngine reminderEngine = new ReminderEngine(questGuild);
        reminderEngine.run(questGuild);

        SteamCommand steamCommand = new SteamCommand(gamestatsManager);
        steamCommand.loadFile(questGuild);

        UptimeCommand uptimeCommand = new UptimeCommand();
        uptimeCommand.run(client);

        commandCategories = ImmutableMap.of(
                "Mods et outils Quest", Arrays.asList(
                        new ListModsCommand(),
                        new InfoModCommand(),
                        new ListToolsCommand(),
                        new InfoToolCommand(),
                        new PatchnoteLinkCommand(),
                        new QuestSetupLinkCommand()
                ),
                "Recherche", Arrays.asList(
                        new YouTubeSearchCommand(),
                        new GoogleSearchCommand(),
                        new GoogleImageSearchCommand(),
                        new WikipediaCommand()
                ),
                "Statistiques de jeu", Arrays.asList(
                        new GamestatsCommand(gamestatsManager),
                        new PlayedCommand(gamestatsManager),
                        new ToggleGamestatsCommand(gamestatsManager),
                        steamCommand
                ),
                "Fonctions sociales (aka \"Plagiat Tatsumaki\")", Arrays.asList(
                        new DailyCommand(levelingManager),
                        new RepCommand(levelingManager),
                        new RepRandomCommand(levelingManager),
                        new GiveCashCommand(levelingManager),
                        new TopCommand(levelingManager),
                        new ProfileCommand(levelingManager),
                        new CashCommand(levelingManager),
                        new RoleCommand(levelingManager),
                        new BackgroundsCommand(levelingManager),
                        new ChooseBackgroundCommand(levelingManager),
                        new ChooseGameBackgroundCommand(levelingManager),
                        new ResetBackgroundCommand(levelingManager)
                ),
                "Rappels", Arrays.asList(
                        new AddReminderCommand(reminderEngine),
                        new ListRemindersCommand(reminderEngine),
                        new RemoveReminderCommand(reminderEngine)
                ),
                "Autres", Arrays.asList(
                        new FlipCommand(),
                        new ChooseCommand(),
                        new RollCommand(),
                        new TimerCommand(),
                        new ChronoCommand(),
                        new TextEmoteCommand(),
                        new AsciiTextCommand(),
                        new SnowflakeCommand(),
                        new URLEncodeCommand()
                ),
                "Commandes d'administration", Arrays.asList(
                        new ShutdownCommand(),
                        new PingCommand(),
                        uptimeCommand
                ),
                "Commandes inutiles", Arrays.asList(
                        new BonjourCommand(),
                        new ReverseGamestatsCommand(gamestatsManager),
                        new SpamCommand(),
                        new TestCommand(),
                        new EasterEggCommand(),
                        new DabCommand(),
                        new CoffeeCommand(),
                        new GenerateEmojiCommand()
                ),
                "Plus d'infos", Collections.singletonList(this)
        );
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        try {
            if (WebhookReposter.onMessageReceived(event)) {
                return;
            }
        } catch (IOException e) {
            log.error("Could not execute webhook reposter, proceeding with regular message handling", e);
        }

        if (event.isFromGuild() && event.getGuild().getIdLong() == SecretConstants.QUEST_COMMUNITY_SERVER_ID) {
            levelingManager.onMessageReceived(event);
        }

        if (event.getAuthor().isBot() || !event.getMessage().getContentRaw().startsWith("!")) {
            return;
        }

        final List<String> commandParsed = new CommandParser(event.getMessage().getContentRaw()).parse();

        commandParsed.set(0, commandParsed.get(0).toLowerCase());

        log.debug("{} sent command {} sur {}", event.getAuthor(), commandParsed, event.getChannel());

        if (commandParsed.size() > 1 && commandParsed.get(0).equals("!")) {
            commandParsed.set(0, "!" + commandParsed.remove(1).toLowerCase());
            log.debug("Trimmed espaces between \"!\" and command: {}", commandParsed);
        }

        BotCommand command = commandCategories.values().stream()
                .flatMap(List::stream)
                .filter(c -> c.getCommandName().equals(commandParsed.get(0).substring(1)))
                .findFirst().orElse(null);

        if (command == null) {
            log.debug("Command not recognized");
            return;
        }

        if (command.isAdminOnly() && event.getAuthor().getIdLong() != SecretConstants.OWNER_ID) {
            log.debug("Command restricted to admins");
            return;
        }

        int mandatoryParameterCount = 0;
        for (String parameter : command.getCommandParameters()) {
            if (parameter.endsWith("*")) mandatoryParameterCount++;
        }

        if (mandatoryParameterCount > commandParsed.size() - 1) {
            log.debug("Not enough parameters passed, expected at least {}", mandatoryParameterCount);
            event.getChannel().sendMessage("Utilisation : " + getHelpMessage(command)).queue();
            return;
        }

        String[] parameters;
        if (command.getCommandParameters().length == 1 && commandParsed.size() > 2) {
            commandParsed.remove(0);
            parameters = new String[]{String.join(" ", commandParsed)};
            log.debug("Single-operand command parameters grouped as one: {}", (Object[]) parameters);
        } else {
            commandParsed.remove(0);
            parameters = new String[commandParsed.size()];
            commandParsed.toArray(parameters);
            log.debug("Command parameters: {}", commandParsed);
        }

        if (!command.areParametersValid(parameters)) {
            log.debug("The command rejected the parameters given");
            event.getChannel().sendMessage("Utilisation : " + getHelpMessage(command)).queue();
            return;
        }

        try {
            command.runCommand(event, parameters);
        } catch (Exception e) {
            log.error("Exception occurred while processing command {} with parameters {}", command.getCommandName(), parameters, e);
            event.getChannel().sendMessage(":boom: Le bot a explosé pendant le traitement de cette commande.\n" +
                    "<@" + SecretConstants.OWNER_ID + ">, au secours !").queue();
        }
    }

    @Override
    public void onMessageReactionAdd(@NotNull MessageReactionAddEvent event) {
        if (event.getUser() == null || event.getUser().isBot()) {
            return;
        }

        for (BotCommand command : commandCategories.values().stream().flatMap(List::stream).toList()) {
            try {
                if (command.processReaction(event, Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()))) {
                    log.debug("Command {} processed reaction {} on message {}",
                            command.getCommandName(), event.getEmoji(), event.getMessageId());
                    break;
                }
            } catch (Exception e) {
                log.error("Exception occurred while processing reaction {} on message {} with command {}!",
                        event.getEmoji(), event.getMessageId(), command.getCommandName(), e);

                event.getChannel().sendMessage(":boom: Le bot a explosé pendant le traitement de la réaction.\n" +
                        "<@" + SecretConstants.OWNER_ID + ">, au secours !").queue();
            }
        }
    }

    private String getHelpMessage(BotCommand command) {
        StringBuilder helpMessage = new StringBuilder();

        helpMessage.append("`!").append(command.getCommandName());

        for (String parameter : command.getCommandParameters()) {
            helpMessage.append(" [").append(parameter).append(']');
        }

        helpMessage.append('`');

        if (command.isAdminOnly()) {
            helpMessage.append(" (commande réservée à Maddie)");
        }

        helpMessage.append("\n**");

        helpMessage.append(command.getShortHelp()).append(".**\n")
                .append(command.getFullHelp());

        return helpMessage.toString().trim();
    }

    // help command

    @Override
    public String getCommandName() {
        return "help";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"commande"};
    }

    @Override
    public String getShortHelp() {
        return "Liste les commandes disponibles, ou donne plus d'aide pour la commande passée en paramètre";
    }

    @Override
    public String getFullHelp() {
        return "";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return parameters.length == 0 || commandCategories.values().stream()
                .flatMap(List::stream)
                .anyMatch(c -> c.getCommandName().equals(parameters[0]));
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        if (parameters.length == 1) {
            BotCommand command = commandCategories.values().stream()
                    .flatMap(List::stream)
                    .filter(c -> c.getCommandName().equals(parameters[0]))
                    .findFirst().orElseThrow();

            event.getChannel().sendMessage(getHelpMessage(command)).queue();
            return;
        }

        StringBuilder help = new StringBuilder("Commandes disponibles :\n\n");

        for (Map.Entry<String, List<BotCommand>> category : commandCategories.entrySet()) {
            boolean empty = true;
            StringBuilder helpCategory = new StringBuilder("**" + category.getKey() + "**\n");

            for (BotCommand command : category.getValue()) {
                if (command.isAdminOnly() && event.getAuthor().getIdLong() != SecretConstants.OWNER_ID) {
                    continue;
                }

                empty = false;

                helpCategory.append("`!").append(command.getCommandName());

                for (String parameter : command.getCommandParameters()) {
                    helpCategory.append(" [").append(parameter).append(']');
                }

                helpCategory.append("` – ").append(command.getShortHelp()).append('\n');
            }

            helpCategory.append('\n');
            if (!empty) help.append(helpCategory);
        }

        event.getAuthor().openPrivateChannel().queue(channel -> {
            Queue<MessageCreateData> allMessages =
                    SplitUtil.split(help.toString(), 2000, true, SplitUtil.Strategy.NEWLINE, SplitUtil.Strategy.ANYWHERE)
                            .stream()
                            .map(split -> new MessageCreateBuilder().setContent(split).build())
                            .collect(Collectors.toCollection(ArrayDeque::new));

            channel.sendMessage(allMessages.poll()).queue(success -> {
                if (event.getChannel().getType() != ChannelType.PRIVATE) {
                    event.getChannel().sendMessage(":white_check_mark: Je t'ai envoyé toutes les commandes que tu peux utiliser en MP.").queue();
                }

                allMessages.forEach(message -> channel.sendMessage(message).queue());
            }, failure -> SplitUtil.split(help.toString(), 2000, true, SplitUtil.Strategy.NEWLINE, SplitUtil.Strategy.ANYWHERE)
                    .stream()
                    .map(split -> new MessageCreateBuilder().setContent(split).build())
                    .forEach(message -> event.getChannel().sendMessage(message).queue()));
        });
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
