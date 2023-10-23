package com.max480.randomstuff.backend.discord.questcommunitybot.quest;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Mod {
    private final String id;
    private final String name;
    private final String version;
    private final String author;
    private final String description;
    private final String modUrl;
    private final String imageUrl;
    private final String webPage;
    private final boolean needTexmod;
    private final boolean hasCheckpointSupport;
    private final long authorDiscordId;

    public Mod(String csvLine) throws IOException {
        String[] line = csvLine.split(";");
        id = csvToString(line[0]);
        name = csvToString(line[1]);
        version = csvToString(line[2]);
        author = csvToString(line[3]);
        description = csvToString(line[4]);
        modUrl = csvToString(line[5]);
        imageUrl = csvToString(line[6]);
        webPage = csvToString(line[7]);
        needTexmod = Boolean.parseBoolean(line[9]);
        hasCheckpointSupport = Boolean.parseBoolean(line[10]);

        // id starts with the Discord ID of the author
        // this is a bold assumption, but this is a static database anyway
        Matcher matchOnId = Pattern.compile("^([0-9]+).*").matcher(id);

        if (matchOnId.matches()) {
            authorDiscordId = Long.parseLong(matchOnId.group(1));
        } else {
            throw new IOException("Did not find discord id in " + id);
        }
    }

    private String csvToString(String csv) {
        return csv.replace("\\n", "<br>").replace("<pv>", ";");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public String getModUrl() {
        return modUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getWebPage() {
        return webPage;
    }

    public boolean isNeedTexmod() {
        return needTexmod;
    }

    public boolean isHasCheckpointSupport() {
        return hasCheckpointSupport;
    }

    public long getAuthorDiscordId() {
        return authorDiscordId;
    }
}
