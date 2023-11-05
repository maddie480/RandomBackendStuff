package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.misc;

import ovh.maddie480.randomstuff.backend.discord.questcommunitybot.BotCommand;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;

import java.io.IOException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

public class FlipCommand implements BotCommand {
    @Override
    public String getCommandName() {
        return "flip";
    }

    @Override
    public String[] getCommandParameters() {
        return new String[]{"message*"};
    }

    @Override
    public String getShortHelp() {
        return "Retourne le message donné à 180°";
    }

    @Override
    public String getFullHelp() {
        return "Par là, j'entends que \"message de test\" devient \"ʇsǝʇ ǝp ǝƃɐssǝɯ\".";
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
        event.getChannel().sendMessage("(╯°□°）╯︵ " + flip(parameters[0])).queue();
    }

    @Override
    public boolean processReaction(MessageReactionAddEvent event, String reaction) throws IOException {
        return false;
    }

    private final Map<String, String> map = new HashMap<>();

    public FlipCommand() {
        map.put("a", "ɐ");
        map.put("b", "q");
        map.put("c", "ɔ");
        map.put("d", "p");
        map.put("e", "ǝ");
        map.put("f", "ɟ");
        map.put("g", "ƃ");
        map.put("h", "ɥ");
        map.put("i", "ᴉ");
        map.put("j", "ɾ");
        map.put("k", "ʞ");
        map.put("m", "ɯ");
        map.put("n", "u");
        map.put("r", "ɹ");
        map.put("t", "ʇ");
        map.put("v", "ʌ");
        map.put("w", "ʍ");
        map.put("y", "ʎ");
        map.put("A", "∀");
        map.put("C", "Ɔ");
        map.put("E", "Ǝ");
        map.put("F", "Ⅎ");
        map.put("G", "פ");
        map.put("H", "H");
        map.put("I", "I");
        map.put("J", "ſ");
        map.put("L", "˥");
        map.put("M", "W");
        map.put("N", "N");
        map.put("P", "Ԁ");
        map.put("T", "┴");
        map.put("U", "∩");
        map.put("V", "Λ");
        map.put("Y", "⅄");
        map.put("1", "Ɩ");
        map.put("2", "ᄅ");
        map.put("3", "Ɛ");
        map.put("4", "ㄣ");
        map.put("5", "ϛ");
        map.put("6", "9");
        map.put("7", "ㄥ");
        map.put("8", "8");
        map.put("9", "6");
        map.put("0", "0");
        map.put(".", "˙");
        map.put(",", "'");
        map.put("'", ",");
        map.put("\"", ",,");
        map.put("`", ",");
        map.put("?", "¿");
        map.put("!", "¡");
        map.put("[", "]");
        map.put("]", "[");
        map.put("(", ")");
        map.put(")", "(");
        map.put("{", "}");
        map.put("}", "{");
        map.put("<", ">");
        map.put(">", "<");
        map.put("&", "⅋");
        map.put("_", "‾");
        map.put("∴", "∵");
        map.put("⁅", "⁆");
        map.put("ɐ", "a");
        map.put("q", "b");
        map.put("ɔ", "c");
        map.put("p", "d");
        map.put("ǝ", "e");
        map.put("ɟ", "f");
        map.put("ƃ", "g");
        map.put("ɥ", "h");
        map.put("ᴉ", "i");
        map.put("ɾ", "j");
        map.put("ʞ", "k");
        map.put("ɯ", "m");
        map.put("u", "n");
        map.put("ɹ", "r");
        map.put("ʇ", "t");
        map.put("ʌ", "v");
        map.put("ʍ", "w");
        map.put("ʎ", "y");
        map.put("∀", "A");
        map.put("Ɔ", "C");
        map.put("Ǝ", "E");
        map.put("Ⅎ", "F");
        map.put("פ", "G");
        map.put("ſ", "J");
        map.put("˥", "L");
        map.put("W", "M");
        map.put("Ԁ", "P");
        map.put("┴", "T");
        map.put("∩", "U");
        map.put("Λ", "V");
        map.put("⅄", "Y");
        map.put("Ɩ", "1");
        map.put("ᄅ", "2");
        map.put("Ɛ", "3");
        map.put("ㄣ", "4");
        map.put("ϛ", "5");
        map.put("ㄥ", "7");
        map.put("˙", ".");
        map.put(",,", "\"");
        map.put("¿", "?");
        map.put("¡", "!");
        map.put("⅋", "&");
        map.put("‾", "_");
        map.put("∵", "∴");
    }

    private String flip(String s) {
        StringBuilder flippedS = new StringBuilder();

        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = s.replaceAll("[^\\p{ASCII}]", "");

        for (int i = s.length() - 1; i >= 0; i--) {
            String aAjouter = s.charAt(i) + "";

            if (map.containsKey(aAjouter)) {
                flippedS.append(map.get(aAjouter));
            } else {
                flippedS.append(map.getOrDefault(aAjouter.toLowerCase(), aAjouter));
            }
        }

        return flippedS.toString();
    }
}
