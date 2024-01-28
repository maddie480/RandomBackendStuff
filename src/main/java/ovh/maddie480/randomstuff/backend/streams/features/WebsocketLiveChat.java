package ovh.maddie480.randomstuff.backend.streams.features;

import org.apache.commons.lang.RandomStringUtils;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.streams.apis.ChatMessage;
import ovh.maddie480.randomstuff.backend.streams.apis.TwitchChatProvider;

import java.net.InetSocketAddress;

/**
 * Transmits the live chat to the LNJ stream through the magic of websockets.
 */
public class WebsocketLiveChat extends WebSocketServer {
    private static final Logger logger = LoggerFactory.getLogger(WebsocketLiveChat.class);

    private WebSocket webSocket;

    public WebsocketLiveChat() {
        super(new InetSocketAddress(11586));
    }

    @Override
    public void onStart() {
        logger.info("Server started!");
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
        logger.info("Client connected!");

        synchronized (this) {
            if (this.webSocket != null) {
                logger.warn("There can only be one client at a time!");
                webSocket.close();
            } else {
                this.webSocket = webSocket;
            }
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String s) {
        logger.warn("Message received: {}", s);
    }

    @Override
    public void onClose(WebSocket webSocket, int i, String s, boolean b) {
        logger.warn("Client disconnected!");

        synchronized (this) {
            if (this.webSocket != webSocket) return;
            this.webSocket = null;
        }
    }

    @Override
    public void onError(WebSocket webSocket, Exception e) {
        logger.warn("Client disconnected!", e);

        synchronized (this) {
            if (this.webSocket != webSocket) return;
            this.webSocket = null;
        }
    }

    public void onMessageReceived(ChatMessage<?> message) {
        WebSocket webSocket;

        synchronized (this) {
            if (this.webSocket == null) return;
            webSocket = this.webSocket;
        }

        JSONObject messageSerialized = new JSONObject();
        messageSerialized.put("platform", message.provider() instanceof TwitchChatProvider ? "twitch" : "youtube");
        messageSerialized.put("author", message.messageSenderName());
        messageSerialized.put("badges", message.badgeUrls());
        messageSerialized.put("message", message.messageContents());
        messageSerialized.put("ack", RandomStringUtils.randomAlphanumeric(20));

        logger.debug("Sending message: {}", messageSerialized.toString(2));
        webSocket.send(messageSerialized.toString());
    }
}
