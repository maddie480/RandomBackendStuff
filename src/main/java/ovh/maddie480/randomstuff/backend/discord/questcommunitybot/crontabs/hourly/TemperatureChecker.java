package ovh.maddie480.randomstuff.backend.discord.questcommunitybot.crontabs.hourly;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemperatureChecker {
    private static final Logger log = LoggerFactory.getLogger(TemperatureChecker.class);

    private static final Pattern cookiePattern = Pattern.compile(".*(^| )mfsession=([^;]+).*");
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

    private static final List<Color> roleColors = Arrays.asList(
            Color.decode("#F1C40F"),
            Color.decode("#E67E22"),
            Color.decode("#E74C3C"),
            Color.decode("#AAAAAA")
    );

    private final Map<Integer, String> warningNames = new HashMap<>();

    private String sunrise, sunset;

    private void loadFile() {
        try (BufferedReader br = new BufferedReader(new FileReader("daylight_settings.txt"))) {
            sunrise = br.readLine();
            sunset = br.readLine();
        } catch (IOException e) {
            log.warn("Could not load sunrise and sunset times", e);
            sunrise = "";
            sunset = "";
        }
    }

    private static String getToken() throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://meteofrance.com");
        connection.setRequestMethod("HEAD");

        if (connection.getHeaderField("Set-Cookie") == null) {
            throw new IOException("Set-Cookie header is missing!");
        }

        Matcher cookieMatcher = cookiePattern.matcher(connection.getHeaderField("Set-Cookie"));
        if (cookieMatcher.matches()) {
            // le cookie est en fait un token JWT encodé avec un très épique code de César
            String encodedToken = cookieMatcher.group(2);
            StringBuilder token = new StringBuilder();
            for (int i = 0; i < encodedToken.length(); i++) {
                char tokenChar = encodedToken.charAt(i);
                char decodedChar;

                if (("" + tokenChar).matches("[a-zA-Z]")) {
                    int magicNumber = tokenChar <= 'Z' ? 65 : 97;
                    decodedChar = (char) (magicNumber + ((int) tokenChar - magicNumber + 13) % 26);
                } else {
                    decodedChar = tokenChar;
                }

                token.append(decodedChar);
            }

            return token.toString();
        } else {
            throw new IOException("Cookie not found in header!");
        }
    }

    private void retrieveWarningNames(String token) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://rpcache-aa.meteofrance.com/internet2018client/2.0/warning/dictionary?domain=" + SecretConstants.WEATHER_WARNING_DOMAIN);
        connection.setRequestProperty("Authorization", "Bearer " + token);

        JSONObject result;
        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            result = new JSONObject(new JSONTokener(is));
        }

        JSONArray phenomenons = result.getJSONArray("phenomenons");

        for (Object o : phenomenons) {
            JSONObject phenomenon = (JSONObject) o;
            warningNames.put(phenomenon.getInt("id"), phenomenon.getString("name"));
        }

        log.debug("I retrieved the name of the warnings: {}", warningNames);
    }

    private void refreshWarnings(String token, TextChannel target) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://rpcache-aa.meteofrance.com/wsft/v3/warning/currentphenomenons?echeance=J0&warning_type=vigilance&domain=" + SecretConstants.WEATHER_WARNING_DOMAIN);
        connection.setRequestProperty("Authorization", "Bearer " + token);

        JSONObject result;
        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            result = new JSONObject(new JSONTokener(is));
        }

        JSONArray phenomenons = result.getJSONArray("phenomenons_max_colors");

        Map<String, Integer> allLevels = new TreeMap<>();
        Map<String, Integer> retrievedLevels = new LinkedHashMap<>();
        Map<String, Integer> alreadyGotLevels = new LinkedHashMap<>();

        // d'abord on parse tout et on trie par ordre alphabétique du phénomène
        for (Object o : phenomenons) {
            JSONObject phenomenon = (JSONObject) o;
            int id = phenomenon.getInt("phenomenon_id");
            int color = phenomenon.getInt("phenomenon_max_color_id");
            allLevels.put(warningNames.get(id), color);
        }

        // puis après on trie par niveau de vigilance, en foutant tout le vert à la poubelle au passage
        for (int level = 4; level > 1; level--) {
            for (Map.Entry<String, Integer> entry : allLevels.entrySet()) {
                if (level == entry.getValue()) {
                    retrievedLevels.put(entry.getKey(), entry.getValue() - 2);
                }
            }
        }

        // et on récupère ce qu'on a déjà
        Guild server = target.getGuild();
        List<Role> botRoles = server.getSelfMember().getRoles();
        for (Role r : botRoles) {
            if (roleColors.contains(r.getColor())) {
                alreadyGotLevels.put(r.getName(), roleColors.indexOf(r.getColor()));
            }
        }

        if (retrievedLevels.isEmpty()) {
            retrievedLevels.put("Pas de vigilance", 3);
        }

        log.debug("Currently showed levels are {}, currently active levels are {}", alreadyGotLevels, retrievedLevels);

        if (!alreadyGotLevels.equals(retrievedLevels)) {
            log.info("We need to replace the showed levels!");

            for (Role r : botRoles) {
                if (roleColors.contains(r.getColor())) {
                    log.debug("Deleting role {}", r);
                    r.delete().complete();
                }
            }

            for (Map.Entry<String, Integer> level : retrievedLevels.entrySet()) {
                String name = level.getKey();
                Color color = roleColors.get(level.getValue());
                log.debug("Creating role {} with color {}", name, color);

                Role r = server.createRole().setName(name).setColor(color).complete();
                server.addRoleToMember(server.getSelfMember(), r).complete();
            }

            target.sendMessage(":warning: Les vigilances météo ont été mises à jour !").queue();
        }
    }

    private void refreshTemperature(String token, TextChannel target) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://rpcache-aa.meteofrance.com/internet2018client/2.0/observation/gridded?" + SecretConstants.WEATHER_PLACE);
        connection.setRequestProperty("Authorization", "Bearer " + token);

        JSONObject result;
        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            result = new JSONObject(new JSONTokener(is));
        }

        float temperature = result.getJSONObject("properties").getJSONObject("gridded").getFloat("T");
        int temperatureRounded = Math.round(temperature);
        log.debug("Got temperature: {}, rounded to {}", temperature, temperatureRounded);

        changeNicknameIfRequired("CCB 2.0 | " + temperatureRounded + "°C", target);
    }

    private void refreshDaylightSettings(String token, TextChannel target) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://rpcache-aa.meteofrance.com/internet2018client/2.0/ephemeris?" + SecretConstants.WEATHER_PLACE);
        connection.setRequestProperty("Authorization", "Bearer " + token);

        JSONObject result;
        try (InputStream is = ConnectionUtils.connectionToInputStream(connection)) {
            result = new JSONObject(new JSONTokener(is));
        }

        OffsetDateTime daylightStart = OffsetDateTime.parse(result.getJSONObject("properties").getJSONObject("ephemeris").getString("sunrise_time"));
        OffsetDateTime daylightEnd = OffsetDateTime.parse(result.getJSONObject("properties").getJSONObject("ephemeris").getString("sunset_time"));
        boolean isLightMode = OffsetDateTime.now().isAfter(daylightStart) && OffsetDateTime.now().isBefore(daylightEnd);

        log.debug("Daylight starts at {} and ends at {} => light is {}", daylightStart, daylightEnd, isLightMode);

        Guild server = target.getGuild();

        if (isLightMode) {
            server.getRoleById(443402541800226837L).getManager().setColor(new Color(89, 95, 111)).queue();
            server.getRoleById(809579511451877397L).getManager().setColor(new Color(222, 178, 50)).queue();
            server.getRoleById(1053043478411624448L).getManager().setColor(new Color(142, 113, 90)).queue();
        } else {
            server.getRoleById(443402541800226837L).getManager().setColor(new Color(255, 224, 238)).queue();
            server.getRoleById(809579511451877397L).getManager().setColor(new Color(255, 205, 58)).queue();
            server.getRoleById(1053043478411624448L).getManager().setColor(new Color(241, 187, 71)).queue();
        }

        ZonedDateTime zonedDaylightStart = daylightStart.atZoneSameInstant(ZoneId.of("Europe/Paris"));
        ZonedDateTime zonedDaylightEnd = daylightEnd.atZoneSameInstant(ZoneId.of("Europe/Paris"));
        while (zonedDaylightStart.getMinute() % 15 != 0) {
            zonedDaylightStart = zonedDaylightStart.plusMinutes(1);
        }
        while (zonedDaylightEnd.getMinute() % 15 != 0) {
            zonedDaylightEnd = zonedDaylightEnd.plusMinutes(1);
        }

        if (!sunrise.equals(zonedDaylightStart.format(timeFormatter)) || !sunset.equals(zonedDaylightEnd.format(timeFormatter))) {
            log.info("Sunrise and sunset times changed! {} -> {}, {} -> {}",
                    sunrise, zonedDaylightStart.format(timeFormatter), sunset, zonedDaylightEnd.format(timeFormatter));

            sunrise = zonedDaylightStart.format(timeFormatter);
            sunset = zonedDaylightEnd.format(timeFormatter);

            target.sendMessage(":information_source: Change la programmation du light theme de **" + sunrise + "** à **" + sunset + "** !")
                    .queue(message -> message.pin().queue());

            try (BufferedWriter bw = new BufferedWriter(new FileWriter("daylight_settings.txt"))) {
                bw.write(sunrise + "\n" + sunset);
            }
        }
    }

    public void checkForUpdates(TextChannel target) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            loadFile();

            String token = getToken();

            if (warningNames.isEmpty()) {
                retrieveWarningNames(token);
            }
            refreshTemperature(token, target);
            refreshWarnings(token, target);
            refreshDaylightSettings(token, target);

            // fulfill signature
            return null;
        });
    }

    private void changeNicknameIfRequired(String nickname, TextChannel target) {
        Member selfMember = target.getGuild().getSelfMember();

        if (!selfMember.getEffectiveName().equals(nickname)) {
            selfMember.modifyNickname(nickname).queue();
        }
    }
}
