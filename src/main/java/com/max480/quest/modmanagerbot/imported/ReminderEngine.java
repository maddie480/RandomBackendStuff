package com.max480.quest.modmanagerbot.imported;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Date;

public class ReminderEngine {
    /**
     * A reminder, that gets saved to disk in serialized form, because, well, the reminder bot should remember it.
     */
    public static class RappelV3 implements Serializable {
        @Serial
        private static final long serialVersionUID = -7600103237848984874L;

        public ZonedDateTime nextOccurence;
        public Duration interval;
        public String message;
        public Long userId;

        @Override
        public String toString() {
            String minimumDuration = null;
            if (interval != null) {
                long hourCount = interval.getSeconds() / 3600;
                if (hourCount == 1) {
                    minimumDuration = "toutes les heures";
                } else if (hourCount % 24 == 0) {
                    long dayCount = hourCount / 24;
                    if (dayCount == 1) {
                        minimumDuration = "tous les jours";
                    } else if (dayCount % 7 == 0) {
                        long weekCount = dayCount / 7;
                        if (weekCount == 1) {
                            minimumDuration = "toutes les semaines";
                        } else {
                            minimumDuration = "toutes les " + weekCount + " semaines";
                        }
                    } else {
                        minimumDuration = "tous les " + dayCount + " jours";
                    }
                } else {
                    minimumDuration = "toutes les " + hourCount + " heures";
                }

            }

            return "\"" + message + "\" le " +
                    new Date(nextOccurence.toInstant().toEpochMilli()).toLocaleString() +
                    (interval == null ? "" : " puis " + minimumDuration);
        }
    }
}
