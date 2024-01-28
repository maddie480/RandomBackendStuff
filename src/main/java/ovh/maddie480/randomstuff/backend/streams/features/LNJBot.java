package ovh.maddie480.randomstuff.backend.streams.features;

import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.streams.apis.ChatMessage;
import ovh.maddie480.randomstuff.backend.streams.apis.IChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.TwitchChatProvider;
import ovh.maddie480.randomstuff.backend.streams.apis.YouTubeChatProvider;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A small Twitch and YouTube bot that listens to the chat of a specific channel, and that can respond to 2 commands:
 * !clip - creates a clip
 * !poll - runs a poll, users can vote using keywords in the chat
 */
public class LNJBot {
    private static final Logger logger = LoggerFactory.getLogger(LNJBot.class);
    private static final Path lnjPollPath = Paths.get("/shared/lnj-poll.json");

    private final WebsocketLiveChat websocketLiveChat;
    private final SHSChatControl shsChatControl;
    private final ClippyTheClipper clipper;

    public static void main(String[] args) throws IOException {
        new LNJBot();
        logger.debug("Startup finished!");

        try {
            // 6 hours
            Thread.sleep(6 * 3_600_000);
            System.exit(0);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private LNJBot() throws IOException {
        new WebsocketHttpServer().start();
        websocketLiveChat = new WebsocketLiveChat();
        websocketLiveChat.start();

        TwitchChatProvider twitchChatProvider = new TwitchChatProvider();
        twitchChatProvider.connect(this::handleChatMessage);

        YouTubeChatProvider youTubeChatProvider = new YouTubeChatProvider(() ->
                twitchChatProvider.sendMessage("Le bot YouTube n'a plus de budget. RIP"));
        youTubeChatProvider.connect(this::handleChatMessage);

        List<IChatProvider<?>> chatProviders = Arrays.asList(twitchChatProvider, youTubeChatProvider);
        chatProviders.forEach(provider -> provider.sendMessage("Je suis prêt !"));

        shsChatControl = new SHSChatControl(chatProviders);
        clipper = new ClippyTheClipper(twitchChatProvider);
    }

    private synchronized <T> void handleChatMessage(ChatMessage<T> message) {
        logger.debug("New message from {}: {}", message.messageSenderName(), message.messageContents());
        websocketLiveChat.onMessageReceived(message);

        LNJPoll poll;
        try (InputStream is = Files.newInputStream(lnjPollPath)) {
            poll = new LNJPoll(new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            logger.error("Could not load LNJ Poll", e);
            return;
        }

        if (message.messageContents().trim().toLowerCase(Locale.ROOT).matches("^! *clip$")) {
            logger.debug("Received a !clip command from " + message.messageSenderName());
            clipper.makeClip(message);

        } else if (message.isAdmin()
                && (message.messageContents().trim().startsWith("!poll ") || message.messageContents().trim().equals("!poll"))) {

            List<String> command = new CommandParser(message.messageContents().trim()).parse();

            if (command.size() < 3) {
                // we need at least a question and an answer!
                message.respond("Tu dois au moins préciser une question et une réponse ! Par exemple : !poll \"à quoi on joue ce soir ?\" \"pizza dude\" \"geopolitical simulator\" freelancer");
            } else {
                String title = command.get(1);
                Set<String> choices = command.stream().skip(2).collect(Collectors.toSet());

                try (OutputStream os = Files.newOutputStream(lnjPollPath)) {
                    IOUtils.write(new LNJPoll(title, choices).toJson().toString(), os, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    logger.error("Could not save new LNJ Poll", e);
                    return;
                }

                logger.debug("New poll created: \"{}\", with choices {}", title, choices);
                message.respond("Sondage créé !");
            }
        } else if (poll.voteFor(message.messageSenderId(), message.messageContents())) {
            logger.debug("New vote received on poll: {} (ID {}) voted {}", message.messageSenderName(),
                    message.messageSenderId(), message.messageContents());

            try (OutputStream os = Files.newOutputStream(lnjPollPath)) {
                IOUtils.write(poll.toJson().toString(), os, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("Could not save LNJ Poll vote", e);
            }
        }

        Matcher tolerantCommandMatcher = Pattern.compile("^! *([a-z0-9_é]+)$")
                .matcher(message.messageContents().trim().toLowerCase(Locale.ROOT));

        if (tolerantCommandMatcher.matches()) {
            shsChatControl.handleCommand("!" + tolerantCommandMatcher.group(1));

            if (message.provider() instanceof YouTubeChatProvider youtube) {
                youtube.respondToFixedCommand(message, tolerantCommandMatcher.group(1));
            }
        }
    }

    public static void healthCheck() throws IOException {
        String title;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/twitch-poll.json")) {
            JSONObject o = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
            title = o.getString("name");
        }

        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/twitch-poll")) {
            if (!IOUtils.toString(is, StandardCharsets.UTF_8).contains(StringEscapeUtils.escapeHtml4(title))) {
                throw new IOException("Poll title wasn't found on the page!");
            }
        }
    }
}
