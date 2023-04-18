package com.max480.randomstuff.backend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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

    // Server Manager bot credentials
    public static String SERVER_JANITOR_TOKEN;
    public static Long SUPPORT_SERVER_ID;
    public static List<Long> SUPPORT_SERVER_CHANNELS_TO_CLEAN_UP;


    // API key allowing to download stuff from Google Drive
    public static String GOOGLE_DRIVE_API_KEY;

    // secret used to call "reload" APIs on the frontend, when everest_update.yaml / mod_search_database.yaml is updated
    public static String RELOAD_SHARED_SECRET;

    // key that has to be used to access the speedrun.com update notifications setup
    public static String SRC_MOD_UPDATE_NOTIFICATIONS_KEY;

    // Discord webhooks to call about GameBanana check issues
    public static List<String> GAMEBANANA_ISSUES_ALERT_HOOKS;

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

        SERVER_JANITOR_TOKEN = secrets.getString("SERVER_JANITOR_TOKEN");
        SUPPORT_SERVER_ID = secrets.getLong("SUPPORT_SERVER_ID");
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

        PERSONAL_NOTIFICATION_WEBHOOK_URL = secrets.getString("PERSONAL_NOTIFICATION_WEBHOOK_URL");

        GITHUB_BASIC_AUTH = secrets.getString("GITHUB_BASIC_AUTH");

        CHINA_MIRROR_UPDATE_WEBHOOK = secrets.getString("CHINA_MIRROR_UPDATE_WEBHOOK");
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
