package com.max480.quest.modmanagerbot;

import java.io.Serial;
import java.io.Serializable;

/**
 * Yet another serializable class that's at an odd place to keep an old class name.
 */
public class QuestToolManager {
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
