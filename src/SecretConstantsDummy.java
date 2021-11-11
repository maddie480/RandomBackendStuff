package com.max480.discord.randombots;

import java.util.Collections;
import java.util.List;

public class SecretConstantsDummy {
    // specifies where the bot should publish errors, and who it should ping about them
    static final Long OWNER_ID = 0L;
    static final Long REPORT_SERVER_ID = 0L;
    static final Long REPORT_SERVER_CHANNEL = 0L;

    // Timezone Bot credentials
    static final Long TIMEZONE_BOT_ID = 0L;
    static final String TIMEZONE_BOT_TOKEN = "";
    static final String TIMEZONE_BOT_TOP_GG_TOKEN = "";

    // Games Bot credentials
    static final Long GAMES_BOT_ID = 0L;
    static final String GAMES_BOT_TOP_GG_TOKEN = "";

    // Mod Structure Verifier credentials
    static final String MOD_STRUCTURE_VERIFIER_TOKEN = "";
    static final String STRAWBERRY_JAM_LOCATION = "";

    // Server Manager bot credentials
    static final String SERVER_MANAGER_TOKEN = "";
    static final Long SUPPORT_SERVER_ID = 0L;
    static final Long SUPPORT_SERVER_PRIVATE_CATEGORY_ID = 0L;
    static final Long MOD_STRUCTURE_VERIFIER_ROLE_ID = 0L;

    // API key allowing to download stuff from Google Drive
    static final String GOOGLE_DRIVE_API_KEY = "";

    // credentials allowing to check out the Gravity Helper repository (will be deleted when the repo goes public)
    static final String GITHUB_USERNAME = "";
    static final String GITHUB_PERSONAL_ACCESS_TOKEN = "";

    // API to call to reload the custom entity catalog, mod search and everest_update.yaml on the frontend
    static final String CUSTOM_ENTITY_CATALOG_RELOAD_API = "";
    static final String EVEREST_UPDATE_RELOAD_API = "";
    static final String MOD_SEARCH_RELOAD_API = "";

    static final String SRC_MOD_UPDATE_NOTIFICATIONS_PAGE = "";

    // Discord webhooks to call about GameBanana check issues
    // the first entry is the "owner" one and will also be called for analysis errors
    static final List<String> GAMEBANANA_ISSUES_ALERT_HOOKS = Collections.emptyList();

    // hooks to call when posting update checker notifications
    static final List<String> UPDATE_CHECKER_HOOKS = Collections.emptyList();

    // hook to call for update checker technical logs
    static final String UPDATE_CHECKER_LOGS_HOOK = "";

    // hook to call for speedrun.com update notifications
    static final String SRC_UPDATE_CHECKER_HOOK = "";

    // Twitter bot settings
    static final Long TWITTER_UPDATE_CHANNEL = 0L;
    static final Long QUEST_UPDATE_CHANNEL = 0L;
    static final String TWITTER_BASIC_AUTH = "";
}
