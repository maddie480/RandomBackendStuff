package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.leveling;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.Utils;

import java.io.IOException;

public class RoleCommand implements BotCommand {
    private final PlagiatTatsumaki levelingEngine;

    public RoleCommand(PlagiatTatsumaki levelingEngine) {
        this.levelingEngine = levelingEngine;
    }

    @Override
    public String getCommandName() {
        return "role";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"nom du rôle*"};
    }

    @Override
    public String getShortHelp() {
        return "Obtenir / Acheter l'un des rôles du serveur";
    }

    @Override
    public String getFullHelp() {
        return """
                Cette commande te permet de :
                - Choisir un rôle parmi Guerrier, Mage, Voleur, Conjurateur ou Marchand. Tu ne peux avoir qu'un seul de ces rôles (par exemple, si tu es Guerrier et que tu tapes `!role voleur`, tu auras le rôle Voleur mais plus le rôle Guerrier).
                - Acheter l'un des rôles payants. La liste complète est dans <#372664922200473615>. Tu peux avoir autant de rôles payants que tu veux.""";
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
        levelingEngine.pickRole(event.getChannel(), event.getMember(), parameters[0]);
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        if (Utils.getUnicodeHexFromEmoji(event.getEmoji().getName()).equals("e29c85")) {
            return levelingEngine.onTickAddBuyRole(event.getChannel(), event.getMessageIdLong(), event.getUser());
        }
        return false;
    }
}
