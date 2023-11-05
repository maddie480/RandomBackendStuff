package com.max480.quest.modmanagerbot;

import java.io.Serial;
import java.io.Serializable;

public class QuestToolManager {
    /**
     * A Quest tool, read from a legacy static file containing... all Quest tools.
     * It used not to be static, with commands to add tools, but the community is dead, so...
     */
    public static class Tool implements Serializable {
        @Serial
        private static final long serialVersionUID = -8252537570477308180L;

        public String name;
        public String version;
        public String author;
        public String shortDescription;
        public String longDescription;
        public String downloadUrl;
        public String moreInfoUrl;
        public String imageUrl;

        @Override
        public String toString() {
            return "**" + name + "** par " + author;
        }
    }
}
