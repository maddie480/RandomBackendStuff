package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.quest;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.max480.quest.modmanagerbot.QuestToolManager.Tool;

public class ListToolsCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(ListToolsCommand.class);

    private final Map<Long, Map<String, String>> reactionMap = new HashMap<>();

    @Override
    public String getCommandName() {
        return "list_tools";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Lister tous les outils / logiciels de la communauté, avec une description rapide";
    }

    @Override
    public String getFullHelp() {
        return "Pour avoir plus d'informations sur un outil en particulier, utiliser la commande `!info_tool [name*]`.";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        Map<String, Tool> tools = getAllQuestTools();

        if (tools.isEmpty()) {
            event.getChannel().sendMessage("Il n'y a rien ici !").queue();
        } else {
            List<Tool> list = new ArrayList<>(tools.values());

            String[] reactions = getAllPossibleReactions();

            StringBuilder builder = new StringBuilder();
            Map<String, String> reactionMap = new LinkedHashMap<>();
            int k = 0;
            while (k < reactions.length && k < list.size()) {
                builder.append(Utils.getEmojiFromUnicodeHex(reactions[k]).getName()).append(" - ").append(list.get(k)).append('\n');
                reactionMap.put(reactions[k], list.get(k).name);
                k++;
            }
            while (k < list.size()) {
                builder.append(" - ").append(list.get(k)).append('\n');
                k++;
            }

            Queue<MessageCreateData> messages =
                    SplitUtil.split("Voici la liste des outils et logiciels de la communauté :\n" + builder + "\n" +
                                            "Pour plus d'informations et les liens de téléchargement, cliquer sur une réaction, taper `!info_tool [nom]` ou visiter https://maddie480.ovh/quest/tools",
                                    2000, true, SplitUtil.Strategy.NEWLINE, SplitUtil.Strategy.ANYWHERE)
                            .stream()
                            .map(split -> new MessageCreateBuilder().setContent(split).build())
                            .collect(Collectors.toCollection(ArrayDeque::new));

            while (!messages.isEmpty()) {
                if (messages.size() == 1) {
                    event.getChannel().sendMessage(messages.poll())
                            .queue(message -> {
                                reactionMap.keySet().forEach(reaction -> message.addReaction(Utils.getEmojiFromUnicodeHex(reaction)).queue());
                                ListToolsCommand.this.reactionMap.put(message.getIdLong(), reactionMap);
                                log.debug("New reaction map => {}", ListToolsCommand.this.reactionMap);

                                Runnable clear = () -> {
                                    ListToolsCommand.this.reactionMap.remove(message.getIdLong());
                                    log.debug("Cleared reactions for timeout, New reaction map => {}", ListToolsCommand.this.reactionMap);
                                };

                                if (event.getChannel() instanceof TextChannel)
                                    message.clearReactions().queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                                else
                                    message.editMessage(message.getContentRaw() + "\n(expiré)").queueAfter(30, TimeUnit.MINUTES, success -> clear.run());
                            });
                } else {
                    event.getChannel().sendMessage(messages.poll()).queue();
                }
            }
        }
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        long messageId = event.getMessageIdLong();
        MessageChannel channel = event.getChannel();

        if (reactionMap.containsKey(messageId) && reactionMap.get(messageId).containsKey(reaction)) {
            String modName = reactionMap.get(messageId).get(reaction);

            Map<String, String> mapmap = reactionMap.get(messageId);
            mapmap.remove(reaction);
            log.debug("Correspond au mod {}, map des réactions = {}", modName, reactionMap);

            if (channel instanceof TextChannel textChannel) {
                textChannel.clearReactionsById(messageId).queue(success -> {
                    for (String reac : mapmap.keySet()) {
                        channel.addReactionById(messageId, Utils.getEmojiFromUnicodeHex(reac)).queue();
                        log.debug("Scheduled reaction add for {} on {}", reac, messageId);
                    }
                });
            }

            if (!getAllQuestTools().containsKey(InfoModCommand.normalize(modName))) {
                channel.sendMessage("Je suis confus, cet outil n'existe plus...").queue();
            } else {
                InfoToolCommand.sendInfoTool(channel, modName);
            }

            return true;
        }

        return false;
    }

    private static String[] getAllPossibleReactions() {
        String[] reactions = new String[36];

        int k = 0;
        for (int i = 0x31e283a3; i <= 0x39e283a3; i += 0x1000000) {
            reactions[k++] = Integer.toHexString(i);
        }
        reactions[k++] = "f09f949f";
        for (int i = 0xf09f87a6; i <= 0xf09f87bf; i++) {
            reactions[k++] = Integer.toHexString(i);
        }

        return reactions;
    }

    static Map<String, Tool> getAllQuestTools() throws IOException {
        try (ObjectInputStream input = new ObjectInputStream(new FileInputStream("/app/static/quest-tools.ser"))) {
            int count = input.readInt();
            Map<String, Tool> tools = new TreeMap<>();

            for (int i = 0; i < count; i++) {
                Tool tool = (Tool) input.readObject();
                tools.put(InfoModCommand.normalize(tool.name), tool);
            }

            return tools;
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
    }
}
