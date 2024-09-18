package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.admin;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public class UptimeCommand implements BotCommand {
    private static final Logger log = LoggerFactory.getLogger(UptimeCommand.class);

    private final ZonedDateTime startDate = ZonedDateTime.now();

    @Override
    public String getCommandName() {
        return "uptime";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[0];
    }

    @Override
    public String getShortHelp() {
        return "Indique depuis combien de temps le Bot tourne";
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
        return true;
    }

    @Override
    public void runCommand(MessageReceivedEvent event, String[] parameters) throws IOException {
        long minutes = startDate.until(ZonedDateTime.now(), ChronoUnit.MINUTES);

        long remainingMinutes = minutes % 60;
        long hours = (minutes / 60) % 24;
        long days = minutes / 24 / 60;

        String s = "Le Bot tourne depuis ";
        if (days != 0) {
            s += (days == 1 ? "1 jour, " : days + " jours, ");
        }
        if (hours != 0) {
            s += (hours == 1 ? "1 heure, " : hours + " heures, ");
        }
        s += (remainingMinutes == 1 ? "1 minute" : remainingMinutes + " minutes");
        s += ".";

        event.getChannel().sendMessage(s).queue();
    }


    public void run(JDA jda) throws IOException {
        new Thread("Uptime Presence Updater") {
            @Override
            public void run() {
                while (true) {
                    long minutes = startDate.until(ZonedDateTime.now(), ChronoUnit.MINUTES);

                    long remainingMinutes = minutes % 60;
                    long hours = (minutes / 60) % 24;
                    long days = minutes / 24 / 60;

                    String s = "Lancé depuis ";
                    if (days != 0) s += days + "j ";
                    if (hours != 0) s += hours + "h ";
                    if (remainingMinutes != 0) s += remainingMinutes + "m ";
                    s = s.trim();

                    jda.getPresence().setActivity(Activity.playing("!help | " + s));

                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException e) {
                        log.error("Sleep interrupted", e);
                    }
                }
            }
        }.start();
    }


    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }
}
