package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ChronoCommand implements BotCommand {
    private static final Logger logger = LoggerFactory.getLogger(ChronoCommand.class);

    private int stopwatchId = 0;
    private final Map<Integer, Boolean> runningStates = new HashMap<>();
    private final Map<Integer, Long> owners = new HashMap<>();
    private final Map<Long, Integer> messageIDsToStopwatchIDs = new HashMap<>();

    @Override
    public String getCommandName() {
        return "chrono";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Déclenche un chrono à la minute près (max 2 heures)";
    }

    @Override
    public String getFullHelp() {
        return "Le chrono est un message mis à jour toutes les minutes dans le channel. " +
                " Tu peux cliquer sur la réaction :x: pour l'arrêter ou :repeat: pour l'arrêter et en relancer un nouveau. " +
                "Attention, le chrono s'arrête automatiquement au bout de 2 heures.";
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
        startStopwatch(event.getAuthor().getIdLong(), event.getChannel());
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        if (!messageIDsToStopwatchIDs.containsKey(event.getMessageIdLong())) {
            return false;
        }

        int stopwatchId = messageIDsToStopwatchIDs.get(event.getMessageIdLong());
        long authorId = event.getUserIdLong();
        String unicode = Utils.getUnicodeHexFromEmoji(event.getEmoji().getName());

        logger.debug("Reaction matched stopwatch id {}", stopwatchId);
        if (authorId != owners.get(stopwatchId)) {
            logger.debug("Reaction is not from the watch owner");
        } else if (unicode.equals("e29d8c")) {
            logger.debug("Stopping");
            runningStates.put(stopwatchId, false);
        } else if (unicode.equals("f09f9481")) {
            logger.debug("Restarting a new watch");
            runningStates.put(stopwatchId, false);
            startStopwatch(authorId, event.getChannel());
        }

        return true;
    }

    private void startStopwatch(long authorId, MessageChannel chan) {
        final int id = ++stopwatchId;
        runningStates.put(id, true);
        owners.put(id, authorId);

        new Thread("Chrono #" + id + " de " + authorId) {
            @Override
            public void run() {
                Message message = null;
                int ecoule = 0;

                String tempsEcoule = "quelques secondes";
                while (runningStates.getOrDefault(id, false) && ecoule <= 120) {
                    tempsEcoule = ecoule + (ecoule == 1 ? " minute" : " minutes");
                    if (ecoule == 0) tempsEcoule = "moins d'une minute";
                    logger.debug(tempsEcoule + " elapsed");

                    if (message == null) {
                        logger.debug("Posting first message");
                        BlockingQueue<Message> messageReceiver = new ArrayBlockingQueue<>(1);

                        chan.sendMessage("Le chrono #" + id + " de <@" + authorId + "> tourne depuis " + tempsEcoule + ".").queue(
                                mess -> {
                                    try {
                                        messageReceiver.put(mess);

                                        messageIDsToStopwatchIDs.put(mess.getIdLong(), id);
                                        mess.addReaction(Utils.getEmojiFromUnicodeHex("e29d8c")).queue();
                                        mess.addReaction(Utils.getEmojiFromUnicodeHex("f09f9481")).queue();
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                });

                        try {
                            message = messageReceiver.take();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        logger.debug("Done. ID is " + message.getId());
                    } else {
                        logger.debug("Updating existing message.");
                        message.editMessage("Le chrono #" + id + " de <@" + authorId + "> tourne depuis " + tempsEcoule + ".").queue();
                    }

                    for (int i = 0; i < 60 && runningStates.getOrDefault(id, false); i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    ecoule++;
                }

                if (message != null) {
                    logger.info("Stopwatch was stopped");
                    message.delete().reason("Nettoyage d'un chrono arrêté").queue();
                    chan.sendMessage("Le chrono #" + id + " a tourné pendant " + tempsEcoule + ".").queue();

                    messageIDsToStopwatchIDs.remove(message.getIdLong());
                    runningStates.remove(id);
                    owners.remove(id);
                    logger.debug("Left stopwatch Ids = {} and running states = {} and owners = {}", messageIDsToStopwatchIDs, runningStates, owners);
                }

                logger.debug("Stopwatch thread stopping");
            }
        }.start();
    }
}
