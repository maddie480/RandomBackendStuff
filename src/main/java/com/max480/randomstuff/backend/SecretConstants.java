package com.max480.randomstuff.backend;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SecretConstants {
    private static Logger logger = LoggerFactory.getLogger(SecretConstants.class);

    // specifies where the bot should publish errors, and who it should ping about them
    public static Long OWNER_ID = 0L;
    public static Long REPORT_SERVER_ID = 0L;
    public static Long REPORT_SERVER_CHANNEL = 0L;

    // Timezone Bot credentials
    public static String TIMEZONE_BOT_TOKEN = "";

    // API key to access the TimeZoneDB API
    public static String TIMEZONEDB_API_KEY = "";

    // Games Bot credentials
    public static String GAMES_BOT_CLIENT_ID = "";
    public static String GAMES_BOT_TOKEN = "";
    public static String GAMES_BOT_TOP_GG_TOKEN = "";

    // Custom Slash Commands application credentials
    public static String CUSTOM_SLASH_COMMANDS_CLIENT_ID = "";
    public static String CUSTOM_SLASH_COMMANDS_CLIENT_SECRET = "";
    public static String CUSTOM_SLASH_COMMANDS_TOKEN = "";
    public static String CUSTOM_SLASH_COMMANDS_TOP_GG_TOKEN = "";

    // Credentials for the frontend ("no roles") version of the Timezone Bot
    public static String TIMEZONE_BOT_LITE_CLIENT_ID = "";
    public static String TIMEZONE_BOT_LITE_TOKEN = "";
    public static String TIMEZONE_BOT_LITE_TOP_GG_TOKEN = "";

    // Mod Structure Verifier credentials
    public static String MOD_STRUCTURE_VERIFIER_TOKEN = "";
    public static String STRAWBERRY_JAM_LOCATION = "";

    // Server Manager bot credentials
    public static String SERVER_JANITOR_TOKEN = "";
    public static Long SUPPORT_SERVER_ID = 0L;
    public static Long SUPPORT_SERVER_HIDE_ROLE_ID = 0L;
    public static List<Long> SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP = Collections.emptyList();


    // API key allowing to download stuff from Google Drive
    public static String GOOGLE_DRIVE_API_KEY = "";

    // secret used to call "reload" APIs on the frontend, when everest_update.yaml / mod_search_database.yaml is updated
    public static String RELOAD_SHARED_SECRET = "";

    // key that has to be used to access the speedrun.com update notifications setup
    public static String SRC_MOD_UPDATE_NOTIFICATIONS_KEY = "";

    // Discord webhooks to call about GameBanana check issues
    public static List<String> GAMEBANANA_ISSUES_ALERT_HOOKS = Collections.emptyList();

    // hooks to call when posting update checker notifications
    public static List<String> UPDATE_CHECKER_HOOKS = Collections.emptyList();

    // hook to call for update checker technical logs
    public static String UPDATE_CHECKER_LOGS_HOOK = "";

    // hooks to call in case of issues with 0x0a.de
    public static List<String> JADE_PLATFORM_HEALTHCHECK_HOOKS = Collections.emptyList();

    // hooks to call in case of issues with platforms other than 0x0a.de (namely GameBanana, Update Checker and max480-random-stuff)
    public static List<String> NON_JADE_PLATFORM_HEALTHCHECK_HOOKS = Collections.emptyList();

    // hook to call for speedrun.com update notifications
    public static String SRC_UPDATE_CHECKER_HOOK = "";

    // Twitter bot settings
    public static String PERSONAL_TWITTER_WEBHOOK_URL = "";
    public static Long QUEST_UPDATE_CHANNEL = 0L;
    public static String TWITTER_BASIC_AUTH = "";

    // header used to authenticate with the GitHub API
    public static String GITHUB_BASIC_AUTH = "";

    // URL used to update the China-accessible mirror of updater files
    public static String CHINA_MIRROR_UPDATE_WEBHOOK = "";

    // other secrets used for private stuff
    public static String SLASH_COMMAND_BOT_TOKEN = "";
    public static String QUEST_COMMUNITY_BOT_TOKEN = "";
    public static String GOOGLE_CUSTOM_SEARCH_API_KEY = "";
    public static String YOUTUBE_API_KEY = "";
    public static String EXPLOIT_PLANNING_URL = "";
    public static List<String> SECRET_WEBHOOKS = Collections.emptyList();
    public static String DUCKDNS_TOKEN = null;
    public static String QUEST_COMMUNITY_BOT_SHARED_SECRET = null;
    public static String TWITCH_CLIENT_ID = null;
    public static String TWITCH_CLIENT_SECRET = null;
    public static String RDV_BASIC_AUTH = null;

    static {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            // BACKEND_SECRETS contains all the secrets, in JSON format.
            SecretVersionName secretVersionName = SecretVersionName.of("max480-random-stuff", "BACKEND_SECRETS", "1");
            AccessSecretVersionResponse response = client.accessSecretVersion(secretVersionName);
            JSONObject secrets = new JSONObject(response.getPayload().getData().toStringUtf8());

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

            MOD_STRUCTURE_VERIFIER_TOKEN = secrets.getString("MOD_STRUCTURE_VERIFIER_TOKEN");
            STRAWBERRY_JAM_LOCATION = secrets.getString("STRAWBERRY_JAM_LOCATION");

            SERVER_JANITOR_TOKEN = secrets.getString("SERVER_JANITOR_TOKEN");
            SUPPORT_SERVER_ID = secrets.getLong("SUPPORT_SERVER_ID");
            SUPPORT_SERVER_HIDE_ROLE_ID = secrets.getLong("SUPPORT_SERVER_HIDE_ROLE_ID");
            SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP = getListOfLongs(secrets.getJSONArray("SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP"));

            GOOGLE_DRIVE_API_KEY = secrets.getString("GOOGLE_DRIVE_API_KEY");

            RELOAD_SHARED_SECRET = secrets.getString("RELOAD_SHARED_SECRET");

            SRC_MOD_UPDATE_NOTIFICATIONS_KEY = secrets.getString("SRC_MOD_UPDATE_NOTIFICATIONS_KEY");

            GAMEBANANA_ISSUES_ALERT_HOOKS = getListOfStrings(secrets.getJSONArray("GAMEBANANA_ISSUES_ALERT_HOOKS"));
            UPDATE_CHECKER_HOOKS = getListOfStrings(secrets.getJSONArray("UPDATE_CHECKER_HOOKS"));
            UPDATE_CHECKER_LOGS_HOOK = secrets.getString("UPDATE_CHECKER_LOGS_HOOK");
            JADE_PLATFORM_HEALTHCHECK_HOOKS = getListOfStrings(secrets.getJSONArray("JADE_PLATFORM_HEALTHCHECK_HOOKS"));
            NON_JADE_PLATFORM_HEALTHCHECK_HOOKS = getListOfStrings(secrets.getJSONArray("NON_JADE_PLATFORM_HEALTHCHECK_HOOKS"));

            SRC_UPDATE_CHECKER_HOOK = secrets.getString("SRC_UPDATE_CHECKER_HOOK");

            PERSONAL_TWITTER_WEBHOOK_URL = secrets.getString("PERSONAL_TWITTER_WEBHOOK_URL");
            QUEST_UPDATE_CHANNEL = secrets.getLong("QUEST_UPDATE_CHANNEL");
            TWITTER_BASIC_AUTH = secrets.getString("TWITTER_BASIC_AUTH");

            GITHUB_BASIC_AUTH = secrets.getString("GITHUB_BASIC_AUTH");

            CHINA_MIRROR_UPDATE_WEBHOOK = secrets.getString("CHINA_MIRROR_UPDATE_WEBHOOK");

            SLASH_COMMAND_BOT_TOKEN = secrets.getString("SLASH_COMMAND_BOT_TOKEN");
            QUEST_COMMUNITY_BOT_TOKEN = secrets.getString("QUEST_COMMUNITY_BOT_TOKEN");
            GOOGLE_CUSTOM_SEARCH_API_KEY = secrets.getString("GOOGLE_CUSTOM_SEARCH_API_KEY");
            YOUTUBE_API_KEY = secrets.getString("YOUTUBE_API_KEY");
            EXPLOIT_PLANNING_URL = secrets.getString("EXPLOIT_PLANNING_URL");
            SECRET_WEBHOOKS = getListOfStrings(secrets.getJSONArray("SECRET_WEBHOOKS"));
            DUCKDNS_TOKEN = secrets.getString("DUCKDNS_TOKEN");
            QUEST_COMMUNITY_BOT_SHARED_SECRET = secrets.getString("QUEST_COMMUNITY_BOT_SHARED_SECRET");
            TWITCH_CLIENT_ID = secrets.getString("TWITCH_CLIENT_ID");
            TWITCH_CLIENT_SECRET = secrets.getString("TWITCH_CLIENT_SECRET");
            RDV_BASIC_AUTH = secrets.getString("RDV_BASIC_AUTH");
        } catch (IOException e) {
            logger.error("Could not load application secrets!", e);
        }
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
}
