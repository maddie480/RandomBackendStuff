package ovh.maddie480.randomstuff.backend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SecretConstants {
    // specifies where the bot should publish errors, and who it should ping about them
    public static Long OWNER_ID;
    public static Long REPORT_SERVER_ID;
    public static Long REPORT_SERVER_CHANNEL;

    // Timezone Bot credentials
    public static String TIMEZONE_BOT_TOKEN;

    // API key to access the TimeZoneDB API
    public static String TIMEZONEDB_API_KEY;

    // Games Bot credentials
    public static String GAMES_BOT_CLIENT_ID;
    public static String GAMES_BOT_TOKEN;
    public static String GAMES_BOT_TOP_GG_TOKEN;

    // Custom Slash Commands application credentials
    public static String CUSTOM_SLASH_COMMANDS_CLIENT_ID;
    public static String CUSTOM_SLASH_COMMANDS_CLIENT_SECRET;
    public static String CUSTOM_SLASH_COMMANDS_TOKEN;
    public static String CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN;

    // Credentials for the frontend ("no roles") version of the Timezone Bot
    public static String TIMEZONE_BOT_LITE_CLIENT_ID;
    public static String TIMEZONE_BOT_LITE_TOKEN;
    public static String TIMEZONE_BOT_LITE_TOP_GG_TOKEN;

    // Token for the BananaBot user that shouldn't be used
    public static String BANANABOT_TOKEN;

    // Mod Structure Verifier credentials
    public static String MOD_STRUCTURE_VERIFIER_TOKEN;

    // Links to helpers from GitHub that should be taken into account by the Mod Structure Verifier (everest.yaml name => path to Lönn lang file)
    public static Map<String, String> LOENN_ENTITIES_FROM_GITHUB;
    public static List<String> EVEREST_YAMLS_FROM_GITHUB;

    // Server Manager bot credentials
    public static String SERVER_JANITOR_TOKEN;
    public static Long SUPPORT_SERVER_ID;
    public static List<Long> SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP;

    // Raw content of the update_checker_config.yaml to pass to the Everest Update Checker
    public static String UPDATE_CHECKER_CONFIG;

    // Asset Drive Browser service: interacts with Google Drive
    public static String GOOGLE_DRIVE_API_KEY;
    public static String ASSET_DRIVE_FOLDER_ID;
    public static String GOOGLE_DRIVE_OAUTH_CONFIG;

    public static String NEXTCLOUD_HEALTHCHECK_TOKEN;

    // secret used to call "reload" APIs on the frontend, when everest_update.yaml / mod_search_database.yaml is updated
    public static String RELOAD_SHARED_SECRET;
    public static String HEALTH_CHECK_CURL_URL;

    // key that has to be used to access the speedrun.com update notifications setup
    public static String SRC_MOD_UPDATE_NOTIFICATIONS_KEY;

    // Discord webhooks to call about GameBanana check issues
    public static List<String> GAMEBANANA_ISSUES_ALERT_HOOKS;

    // The channel on Celestecord where people running collabs should be pinged when they are auto-hidden from the list
    public static String COLLAB_AUTO_HIDDEN_ALERT_HOOK;

    // hooks to call when posting update checker notifications
    public static List<String> UPDATE_CHECKER_HOOKS;

    // hook to call for update checker technical logs
    public static String UPDATE_CHECKER_LOGS_HOOK;

    // hooks to call in case of issues with 0x0a.de
    public static List<String> JADE_PLATFORM_HEALTHCHECK_HOOKS;

    // hooks to call in case of issues with platforms other than 0x0a.de (namely GameBanana, Update Checker and maddie480.ovh)
    public static List<String> NON_JADE_PLATFORM_HEALTHCHECK_HOOKS;

    // hook to call for speedrun.com update notifications
    public static String SRC_UPDATE_CHECKER_HOOK;

    // a webhook I'm using for personal notifications
    public static String PERSONAL_NOTIFICATION_WEBHOOK_URL;

    // header used to authenticate with the GitHub API
    public static String GITHUB_BASIC_AUTH;

    // URL used to update the China-accessible mirror of updater files
    public static String CHINA_MIRROR_UPDATE_WEBHOOK;

    // Twitch credentials
    public static String TWITCH_CLIENT_ID;
    public static String TWITCH_CLIENT_SECRET;

    public static String YOUTUBE_LNJ_BOT_CLIENT_ID;
    public static String YOUTUBE_LNJ_BOT_CLIENT_SECRET;

    // Quest Community Bot secrets
    public static String QUEST_COMMUNITY_BOT_TOKEN;
    public static long QUEST_COMMUNITY_SERVER_ID;
    public static long LEVELING_NOTIFICATION_CHANNEL;
    public static String YOUTUBE_API_KEY;
    public static Map<String, String> YOUTUBE_PRESELECTED_PLAYLISTS;
    public static String GOOGLE_CUSTOM_SEARCH_API_KEY;
    public static String STEAM_WEB_API_KEY;
    public static String REPOST_WEBHOOK_URL;

    // Slash Command Bot secrets
    public static String SLASH_COMMAND_BOT_TOKEN;
    public static Map<String, String> SLASH_COMMAND_TO_TOKEN;
    public static Map<String, String> PEOPLE_TO_DISCORD_IDS;
    public static String EXPLOIT_PLANNING_URL;

    // Quest Community Bot crontabs
    public static String BUS_URL;
    public static String STONK_URL;
    public static String WEATHER_WARNING_DOMAIN;
    public static String WEATHER_PLACE;
    public static List<String> SLASH_COMMAND_BOT_HEALTHCHECKS;

    static {
        // The SECRET_CONSTANTS environment variable has all secrets, in JSON format.
        String environment = System.getenv("SECRET_CONSTANTS");
        JSONObject secrets = new JSONObject(environment);

        OWNER_ID = secrets.getLong("OWNER_ID");
        REPORT_SERVER_ID = secrets.getLong("REPORT_SERVER_ID");
        REPORT_SERVER_CHANNEL = secrets.getLong("REPORT_SERVER_CHANNEL");

        TIMEZONE_BOT_TOKEN = secrets.getString("TIMEZONE_BOT_TOKEN");

        TIMEZONEDB_API_KEY = secrets.getString("TIMEZONEDB_API_KEY");

        GAMES_BOT_CLIENT_ID = secrets.getString("GAMES_BOT_CLIENT_ID");
        GAMES_BOT_TOKEN = secrets.getString("GAMES_BOT_TOKEN");
        GAMES_BOT_TOP_GG_TOKEN = secrets.getString("GAMES_BOT_TOP_GG_TOKEN");

        CUSTOM_SLASH_COMMANDS_CLIENT_ID = secrets.getString("CUSTOM_SLASH_COMMANDS_CLIENT_ID");
        CUSTOM_SLASH_COMMANDS_CLIENT_SECRET = secrets.getString("CUSTOM_SLASH_COMMANDS_CLIENT_SECRET");
        CUSTOM_SLASH_COMMANDS_TOKEN = secrets.getString("CUSTOM_SLASH_COMMANDS_TOKEN");
        CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN = secrets.getString("CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN");

        TIMEZONE_BOT_LITE_CLIENT_ID = secrets.getString("TIMEZONE_BOT_LITE_CLIENT_ID");
        TIMEZONE_BOT_LITE_TOKEN = secrets.getString("TIMEZONE_BOT_LITE_TOKEN");
        TIMEZONE_BOT_LITE_TOP_GG_TOKEN = secrets.getString("TIMEZONE_BOT_LITE_TOP_GG_TOKEN");

        BANANABOT_TOKEN = secrets.getString("BANANABOT_TOKEN");

        MOD_STRUCTURE_VERIFIER_TOKEN = secrets.getString("MOD_STRUCTURE_VERIFIER_TOKEN");

        LOENN_ENTITIES_FROM_GITHUB = getMapOfStrings(secrets.getJSONObject("LOENN_ENTITIES_FROM_GITHUB"));
        EVEREST_YAMLS_FROM_GITHUB = getListOfStrings(secrets.getJSONArray("EVEREST_YAMLS_FROM_GITHUB"));

        SERVER_JANITOR_TOKEN = secrets.getString("SERVER_JANITOR_TOKEN");
        SUPPORT_SERVER_ID = secrets.getLong("SUPPORT_SERVER_ID");
        SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP = getListOfLongs(secrets.getJSONArray("SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP"));

        UPDATE_CHECKER_CONFIG = secrets.getString("UPDATE_CHECKER_CONFIG");

        GOOGLE_DRIVE_API_KEY = secrets.getString("GOOGLE_DRIVE_API_KEY");
        ASSET_DRIVE_FOLDER_ID = secrets.getString("ASSET_DRIVE_FOLDER_ID");
        GOOGLE_DRIVE_OAUTH_CONFIG = secrets.getString("GOOGLE_DRIVE_OAUTH_CONFIG");

        NEXTCLOUD_HEALTHCHECK_TOKEN = secrets.getString("NEXTCLOUD_HEALTHCHECK_TOKEN");

        RELOAD_SHARED_SECRET = secrets.getString("RELOAD_SHARED_SECRET");
        HEALTH_CHECK_CURL_URL = secrets.getString("HEALTH_CHECK_CURL_URL");

        SRC_MOD_UPDATE_NOTIFICATIONS_KEY = secrets.getString("SRC_MOD_UPDATE_NOTIFICATIONS_KEY");

        GAMEBANANA_ISSUES_ALERT_HOOKS = getListOfStrings(secrets.getJSONArray("GAMEBANANA_ISSUES_ALERT_HOOKS"));
        COLLAB_AUTO_HIDDEN_ALERT_HOOK = secrets.getString("COLLAB_AUTO_HIDDEN_ALERT_HOOK");
        UPDATE_CHECKER_HOOKS = getListOfStrings(secrets.getJSONArray("UPDATE_CHECKER_HOOKS"));
        UPDATE_CHECKER_LOGS_HOOK = secrets.getString("UPDATE_CHECKER_LOGS_HOOK");
        JADE_PLATFORM_HEALTHCHECK_HOOKS = getListOfStrings(secrets.getJSONArray("JADE_PLATFORM_HEALTHCHECK_HOOKS"));
        NON_JADE_PLATFORM_HEALTHCHECK_HOOKS = getListOfStrings(secrets.getJSONArray("NON_JADE_PLATFORM_HEALTHCHECK_HOOKS"));

        SRC_UPDATE_CHECKER_HOOK = secrets.getString("SRC_UPDATE_CHECKER_HOOK");

        PERSONAL_NOTIFICATION_WEBHOOK_URL = secrets.getString("PERSONAL_NOTIFICATION_WEBHOOK_URL");

        GITHUB_BASIC_AUTH = secrets.getString("GITHUB_BASIC_AUTH");

        CHINA_MIRROR_UPDATE_WEBHOOK = secrets.getString("CHINA_MIRROR_UPDATE_WEBHOOK");

        TWITCH_CLIENT_ID = secrets.getString("TWITCH_CLIENT_ID");
        TWITCH_CLIENT_SECRET = secrets.getString("TWITCH_CLIENT_SECRET");

        YOUTUBE_LNJ_BOT_CLIENT_ID = secrets.getString("YOUTUBE_LNJ_BOT_CLIENT_ID");
        YOUTUBE_LNJ_BOT_CLIENT_SECRET = secrets.getString("YOUTUBE_LNJ_BOT_CLIENT_SECRET");

        QUEST_COMMUNITY_BOT_TOKEN = secrets.getString("QUEST_COMMUNITY_BOT_TOKEN");
        QUEST_COMMUNITY_SERVER_ID = secrets.getLong("QUEST_COMMUNITY_SERVER_ID");
        LEVELING_NOTIFICATION_CHANNEL = secrets.getLong("LEVELING_NOTIFICATION_CHANNEL");
        YOUTUBE_API_KEY = secrets.getString("YOUTUBE_API_KEY");
        YOUTUBE_PRESELECTED_PLAYLISTS = getMapOfStrings(secrets.getJSONObject("YOUTUBE_PRESELECTED_PLAYLISTS"));
        GOOGLE_CUSTOM_SEARCH_API_KEY = secrets.getString("GOOGLE_CUSTOM_SEARCH_API_KEY");
        STEAM_WEB_API_KEY = secrets.getString("STEAM_WEB_API_KEY");
        REPOST_WEBHOOK_URL = secrets.getString("REPOST_WEBHOOK_URL");

        SLASH_COMMAND_BOT_TOKEN = secrets.getString("SLASH_COMMAND_BOT_TOKEN");
        SLASH_COMMAND_TO_TOKEN = getMapOfStrings(secrets.getJSONObject("SLASH_COMMAND_TO_TOKEN"));
        PEOPLE_TO_DISCORD_IDS = getMapOfStrings(secrets.getJSONObject("PEOPLE_TO_DISCORD_IDS"));
        EXPLOIT_PLANNING_URL = secrets.getString("EXPLOIT_PLANNING_URL");

        BUS_URL = secrets.getString("BUS_URL");
        STONK_URL = secrets.getString("STONK_URL");
        WEATHER_WARNING_DOMAIN = secrets.getString("WEATHER_WARNING_DOMAIN");
        WEATHER_PLACE = secrets.getString("WEATHER_PLACE");
        SLASH_COMMAND_BOT_HEALTHCHECKS = getListOfStrings(secrets.getJSONArray("SLASH_COMMAND_BOT_HEALTHCHECKS"));
    }

    private static List<String> getListOfStrings(JSONArray array) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            result.add(array.getString(i));
        }
        return result;
    }

    private static List<Long> getListOfLongs(JSONArray array) {
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            result.add(array.getLong(i));
        }
        return result;
    }

    private static Map<String, String> getMapOfStrings(JSONObject object) {
        Map<String, String> result = new HashMap<>();
        for (String key : object.keySet()) {
            result.put(key, object.getString(key));
        }
        return result;
    }
}
