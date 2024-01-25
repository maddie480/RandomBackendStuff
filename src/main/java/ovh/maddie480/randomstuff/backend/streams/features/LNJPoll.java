package ovh.maddie480.randomstuff.backend.streams.features;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Represents the ongoing poll on the stream, and handles people responding to it.
 */
class LNJPoll {
    private final long id;
    private final String name;
    private final Map<String, String> answersWithCase;
    private final Map<String, String> answersByUser;

    public LNJPoll(String name, Set<String> answers) {
        this.id = System.currentTimeMillis();
        this.name = name;
        this.answersWithCase = new HashMap<>();
        for (String answer : answers) {
            this.answersWithCase.put(answer.toLowerCase(), answer);
        }
        this.answersByUser = new HashMap<>();
    }

    public LNJPoll(JSONObject json) {
        id = json.getLong("id");
        name = json.getString("name");
        answersWithCase = toMap(json.getJSONObject("answersWithCase"));
        answersByUser = toMap(json.getJSONObject("answersByUser"));
    }

    private static Map<String, String> toMap(JSONObject json) {
        Map<String, String> result = new HashMap<>();
        for (String key : json.keySet()) {
            result.put(key, json.getString(key));
        }
        return result;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("answersWithCase", answersWithCase);
        o.put("answersByUser", answersByUser);
        return o;
    }

    public boolean voteFor(String userId, String vote) {
        vote = vote.toLowerCase();

        if (answersWithCase.containsKey(vote)) {
            answersByUser.put(userId, vote);
            return true;
        } else {
            return false;
        }
    }
}
