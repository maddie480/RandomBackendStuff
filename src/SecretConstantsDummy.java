package com.max480.discord.randombots;

import java.util.Collections;
import java.util.List;

public class SecretConstantsDummy {
    // specifies where the bot should publish errors, and who it should ping about them
    static final Long OWNER_ID = 0L;
    static final Long REPORT_SERVER_ID = 0L;
    static final Long REPORT_SERVER_CHANNEL = 0L;

    // bot credentials
    static final String TIMEZONE_BOT_TOKEN = "";
    static final String MOD_STRUCTURE_VERIFIER_TOKEN = "";

    // the timezone bot currently only support one server, which ID should be filled out here
    static final Long TIMEZONE_BOT_SERVER = 0L;

    // API key allowing to download stuff from Google Drive
    static final String GOOGLE_DRIVE_API_KEY = "";

    // credentials allowing to check out the Gravity Helper repository (will be deleted when the repo goes public)
    static final String GITHUB_USERNAME = "";
    static final String GITHUB_PERSONAL_ACCESS_TOKEN = "";

    // Discord webhooks to call about yield return orig(self) issues
    // the first entry is the "owner" one and will also be called for analysis errors
    static final List<String> YIELD_RETURN_ALERT_HOOKS = Collections.emptyList();
}
