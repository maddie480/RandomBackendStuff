package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

public class TimerCommand implements BotCommand {

    private static final Logger logger = LoggerFactory.getLogger(TimerCommand.class);

    private final Map<Long, Function<Long, Boolean>> messageIDsToTimerStopHooks = new HashMap<>();

    @Override
    public String getCommandName() {
        return "timer";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"durée en minutes*"};
    }

    @Override
    public String getShortHelp() {
        return "Déclenche un compte à rebours (max 2 heures)";
    }

    @Override
    public String getFullHelp() {
        return "Le compte à rebours est un message mis à jour toutes les minutes dans le channel. Tu seras mentionné quand il se terminera." +
                " Tu peux cliquer sur la réaction :x: pour l'annuler avant qu'il se termine.";
    }

    @Override
    public boolean isAdminOnly() {
        return false;
    }

    @Override
    public boolean areParametersValid(String[] parameters) {
        try {
            Integer.parseInt(parameters[0]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        int minutes = Integer.parseInt(parameters[0]);

        MessageChannel chan = event.getChannel();
        long authorId = event.getAuthor().getIdLong();

        if (minutes < 1) {
            chan.sendMessage("<@" + authorId + "> Ton compte à rebours est terminé !!\n" +
                    "(Ouais bah t'avais qu'à me donner un nombre positif toi aussi, hein...)").queue();
            return;
        } else if (minutes > 120) {
            chan.sendMessage("Désolé, les comptes à rebours sont limités à 2 heures !").queue();
            return;
        }

        new Thread("Timer de " + minutes + " min.") {
            private boolean timerIsStopping = false;

            private boolean stopTimer(long reactionAuthorId) {
                if (authorId != reactionAuthorId) {
                    logger.info("Rejecting, wrong author");
                    return false;
                }

                logger.debug("Stopping timer...");
                timerIsStopping = true;
                return true;
            }

            @Override
            public void run() {
                Message message = null;

                String dernierTempsRestant = null;

                for (int restant = minutes; restant > 0 && !timerIsStopping; restant--) {
                    String tempsRestant = restant + (restant == 1 ? " minute" : " minutes");
                    logger.debug(tempsRestant + " remaining");
                    dernierTempsRestant = tempsRestant;

                    if (message == null) {
                        logger.debug("Posting first message");
                        BlockingQueue<Message> messageReceiver = new ArrayBlockingQueue<>(1);

                        chan.sendMessage("Compte à rebours de <@" + authorId + "> : il reste " + tempsRestant + ".\n" +
                                "`[                                        ]`").queue(
                                mess -> {
                                    try {
                                        messageReceiver.put(mess);

                                        messageIDsToTimerStopHooks.put(mess.getIdLong(), this::stopTimer);
                                        mess.addReaction(Utils.getEmojiFromUnicodeHex("e29d8c")).queue();
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
                        int progress = (int) ((100 - (100 * restant / minutes)) / 2.5);
                        StringBuilder progressBar = new StringBuilder("`[");
                        for (int i = 1; i < 41; i++) {
                            if (i <= progress) progressBar.append('=');
                            else progressBar.append(" ");
                        }
                        progressBar.append("]`");
                        logger.debug("Updating existing message.");
                        message.editMessage("Compte à rebours de <@" + authorId + "> : il reste " + tempsRestant + ".\n" +
                                progressBar).queue();
                    }

                    for (int i = 0; i < 60 && !timerIsStopping; i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                if (message != null) {
                    if (timerIsStopping) {
                        logger.info("Timer was stopped");
                        message.delete().reason("Nettoyage d'un compte à rebours annulé").queue();
                        chan.sendMessage("<@" + authorId + "> a annulé son compte à rebours à " + dernierTempsRestant + " de la fin.").queue();
                    } else {
                        logger.info("TIMER IS DUE!");
                        message.delete().reason("Nettoyage d'un compte à rebours terminé").queue();
                        chan.sendMessage("<@" + authorId + "> Ton compte à rebours est terminé !!").queue();
                    }

                    messageIDsToTimerStopHooks.remove(message.getIdLong());
                    logger.debug("Left timer stop hooks = {}", messageIDsToTimerStopHooks);
                }

                logger.debug("Timer thread stopping");
            }
        }.start();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        long messageId = event.getMessageIdLong();
        long authorId = event.getUserIdLong();

        if (Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()).equals("e29d8c") && messageIDsToTimerStopHooks.containsKey(messageId)) {
            logger.debug("Matches a stop command for a timer");
            messageIDsToTimerStopHooks.get(messageId).apply(authorId);
            return true;
        }

        return false;
    }
}
