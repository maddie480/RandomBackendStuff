package com.max480.discord.slashcommandbot;

import org.apache.commons.lang3.tuple.Pair;

import java.io.Serial;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Yet another serializable class that's at an odd place to keep an old class name.
 */
public class SlashCommandBot {
    public static class PlanningExploit implements Serializable {
        @Serial
        private static final long serialVersionUID = 56185131613831863L;

        public final List<ZonedDateTime> exploitTimes = new ArrayList<>();
        public final List<String> principalExploit = new ArrayList<>();
        public final List<String> backupExploit = new ArrayList<>();

        public final Map<String, List<Pair<ZonedDateTime, ZonedDateTime>>> holidays = new HashMap<>();
    }
}
