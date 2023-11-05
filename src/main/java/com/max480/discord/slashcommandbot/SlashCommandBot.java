package com.max480.discord.slashcommandbot;

import org.apache.commons.lang3.tuple.Pair;

import java.io.Serial;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SlashCommandBot {
    /**
     * This planning is provided to some private service in order to answer to a Mattermost slash command.
     * (Mattermost is similar to Slack, except self-hosted. This is work stuff.)
     * (... for a place I don't work at anymore since 2021.)
     */
    public static class PlanningExploit implements Serializable {
        @Serial
        private static final long serialVersionUID = 56185131613831863L;

        public final List<ZonedDateTime> exploitTimes = new ArrayList<>();
        public final List<String> principalExploit = new ArrayList<>();
        public final List<String> backupExploit = new ArrayList<>();

        public final Map<String, List<Pair<ZonedDateTime, ZonedDateTime>>> holidays = new HashMap<>();
    }
}
