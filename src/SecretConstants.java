package com.max480.discord.randombots;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

public class SecretConstants {
    private static final Logger logger = Logger.getLogger("SecretConstants");

    // specifies where the bot should publish errors, and who it should ping about them
    public static Long OWNER_ID = 0L;
    public static Long REPORT_SERVER_ID = 0L;
    public static Long REPORT_SERVER_CHANNEL = 0L;

    // Timezone Bot credentials
    public static Long TIMEZONE_BOT_ID = 0L;
    public static String TIMEZONE_BOT_TOKEN = "";
    public static String TIMEZONE_BOT_TOP_GG_TOKEN = "";

    // Games Bot credentials
    public static Long GAMES_BOT_ID = 0L;
    public static String GAMES_BOT_TOKEN = "";
    public static String GAMES_BOT_TOP_GG_TOKEN = "";

    // Mod Structure Verifier credentials
    public static String MOD_STRUCTURE_VERIFIER_TOKEN = "";
    public static String STRAWBERRY_JAM_LOCATION = "";

    // Server Manager bot credentials
    public static String SERVER_MANAGER_TOKEN = "";
    public static Long SUPPORT_SERVER_ID = 0L;
    public static Long SUPPORT_SERVER_PRIVATE_CATEGORY_ID = 0L;
    public static Long MOD_STRUCTURE_VERIFIER_ROLE_ID = 0L;

    // API key allowing to download stuff from Google Drive
    public static String GOOGLE_DRIVE_API_KEY = "";

    // credentials allowing to check out the Gravity Helper repository (will be deleted when the repo goes public)
    public static String GITHUB_USERNAME = "";
    public static String GITHUB_PERSONAL_ACCESS_TOKEN = "";

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

    // other secrets used for private stuff
    public static String SLASH_COMMAND_BOT_TOKEN = "";
    public static String QUEST_COMMUNITY_BOT_TOKEN = "";
    public static String GOOGLE_CUSTOM_SEARCH_API_KEY = "";
    public static String YOUTUBE_API_KEY = "";

    static {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            // BACKEND_SECRETS contains all the secrets, in JSON format.
            SecretVersionName secretVersionName = SecretVersionName.of("max480-random-stuff", "BACKEND_SECRETS", "1");
            AccessSecretVersionResponse response = client.accessSecretVersion(secretVersionName);
            JSONObject secrets = new JSONObject(response.getPayload().getData().toStringUtf8());

            OWNER_ID = secrets.getLong("OWNER_ID");
            REPORT_SERVER_ID = secrets.getLong("REPORT_SERVER_ID");
            REPORT_SERVER_CHANNEL = secrets.getLong("REPORT_SERVER_CHANNEL");

            TIMEZONE_BOT_ID = secrets.getLong("TIMEZONE_BOT_ID");
            TIMEZONE_BOT_TOKEN = secrets.getString("TIMEZONE_BOT_TOKEN");
            TIMEZONE_BOT_TOP_GG_TOKEN = secrets.getString("TIMEZONE_BOT_TOP_GG_TOKEN");

            GAMES_BOT_ID = secrets.getLong("GAMES_BOT_ID");
            GAMES_BOT_TOKEN = secrets.getString("GAMES_BOT_TOKEN");
            GAMES_BOT_TOP_GG_TOKEN = secrets.getString("GAMES_BOT_TOP_GG_TOKEN");

            MOD_STRUCTURE_VERIFIER_TOKEN = secrets.getString("MOD_STRUCTURE_VERIFIER_TOKEN");
            STRAWBERRY_JAM_LOCATION = secrets.getString("STRAWBERRY_JAM_LOCATION");

            SERVER_MANAGER_TOKEN = secrets.getString("SERVER_MANAGER_TOKEN");
            SUPPORT_SERVER_ID = secrets.getLong("SUPPORT_SERVER_ID");
            SUPPORT_SERVER_PRIVATE_CATEGORY_ID = secrets.getLong("SUPPORT_SERVER_PRIVATE_CATEGORY_ID");
            MOD_STRUCTURE_VERIFIER_ROLE_ID = secrets.getLong("MOD_STRUCTURE_VERIFIER_ROLE_ID");

            GOOGLE_DRIVE_API_KEY = secrets.getString("GOOGLE_DRIVE_API_KEY");

            GITHUB_USERNAME = secrets.getString("GITHUB_USERNAME");
            GITHUB_PERSONAL_ACCESS_TOKEN = secrets.getString("GITHUB_PERSONAL_ACCESS_TOKEN");

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

            SLASH_COMMAND_BOT_TOKEN = secrets.getString("SLASH_COMMAND_BOT_TOKEN");
            QUEST_COMMUNITY_BOT_TOKEN = secrets.getString("QUEST_COMMUNITY_BOT_TOKEN");
            GOOGLE_CUSTOM_SEARCH_API_KEY = secrets.getString("GOOGLE_CUSTOM_SEARCH_API_KEY");
            YOUTUBE_API_KEY = secrets.getString("YOUTUBE_API_KEY");
        } catch (IOException e) {
            logger.severe("Could not load application secrets! " + e.toString());
        }
    }

    private static List<String> getListOfStrings(JSONArray array) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            result.add(array.getString(i));
        }
        return result;
    }
}
