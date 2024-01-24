package ovh.maddie480.randomstuff.backend.streams.features;

import java.util.ArrayList;
import java.util.List;

public class CommandParser {
    private String commandToParse;

    public CommandParser(String commandToParse) {
        this.commandToParse = commandToParse;
    }

    public List<String> parse() {
        List<String> result = new ArrayList<>();

        StringBuilder currentParameter = null;
        boolean isEscaping = false;
        boolean isQuoted = false;
        while (hasMoreTokens()) {
            char nextToken = nextToken();

            if (currentParameter == null) {
                // outside a parameter: look for the next non-space
                if (!Character.isWhitespace(nextToken)) {
                    if (nextToken == '\\') {
                        // parameter that starts with an escaped character
                        isQuoted = false;
                        isEscaping = true;
                        currentParameter = new StringBuilder();
                    } else if (nextToken == '"') {
                        // start of quoted parameter
                        isQuoted = true;
                        currentParameter = new StringBuilder();
                    } else {
                        // first character of a non-quoted parameter
                        isQuoted = false;
                        currentParameter = new StringBuilder("" + nextToken);
                    }
                }
            } else if (isEscaping) {
                // character following a \ => take it as is
                isEscaping = false;
                currentParameter.append(nextToken);
            } else if (isQuoted) {
                // we are inside a quoted parameter => go on until the closing quote
                if (nextToken == '\\') {
                    // escape character
                    isEscaping = true;
                } else if (nextToken == '"') {
                    // we found the closing quote
                    result.add(currentParameter.toString());
                    currentParameter = null;
                } else {
                    // just go on
                    currentParameter.append(nextToken);
                }
            } else {
                // we are inside a non-quoted parameter => go on until the next space
                if (nextToken == '\\') {
                    // escape character
                    isEscaping = true;
                } else if (Character.isWhitespace(nextToken)) {
                    // end of the parameter
                    result.add(currentParameter.toString());
                    currentParameter = null;
                } else {
                    // just go on
                    currentParameter.append(nextToken);
                }
            }
        }

        // we are done, process the last parameter
        if (currentParameter != null) {
            if (isQuoted) {
                // unclosed quote => include the opening quote in the parameter
                currentParameter.insert(0, '"');
            }
            result.add(currentParameter.toString());
        }

        return result;
    }

    private boolean hasMoreTokens() {
        return !commandToParse.isEmpty();
    }

    private char nextToken() {
        char next = commandToParse.charAt(0);
        commandToParse = commandToParse.substring(1);
        return next;
    }
}
