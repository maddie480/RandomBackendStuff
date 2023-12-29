package ovh.maddie480.randomstuff.backend.twitch;

import com.github.twitch4j.chat.TwitchChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Chat control for an obscure game called Streatham Hill Stories.
 * Powered by the magic of TCP sockets!
 */
public class SHSChatControl implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(SHSChatControl.class);

    private static final List<String> COMMANDS = Arrays.asList(
            "!zombie", "!clio", "!audir8", "!transform", "!enter_bowling", "!enter_caesars",
            "!police", "!gravity", "!poop", "!snow", "!rain", "!upsidedown", "!tiny", "!flip"
    );

    private final TwitchChat chat;

    private int sessionId = 0;

    private BlockingQueue<Byte> commandQueue;

    public SHSChatControl(TwitchChat chat) {
        this.chat = chat;
    }

    public void run() {
        new Thread("SHS Server Socket") {
            @Override
            public void run() {
                try (ServerSocket serverSocket = new ServerSocket(11584)) {
                    while (true) {
                        try (Socket socket = serverSocket.accept()) {
                            logger.info("Client connected!");
                            commandQueue = new ArrayBlockingQueue<>(100);
                            handleClient(socket, sessionId);

                        } catch (IOException e) {
                            logger.warn("Client disconnected!", e);
                            commandQueue = null;
                            sessionId++;
                        }
                    }
                } catch (IOException e) {
                    logger.error("Error while starting server socket!", e);
                }
            }
        }.start();
    }

    public void handleCommand(String command) {
        if (!COMMANDS.contains(command) || commandQueue == null) return;

        try {
            commandQueue.put((byte) (COMMANDS.indexOf(command) + 1));
            logger.debug("Command {} queued up!", command);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleClient(Socket socket, int sessionId) throws IOException {
        try (InputStream is = socket.getInputStream();
             OutputStream os = socket.getOutputStream()) {

            {
                // hi! - 42
                int handshake = is.read();
                if (handshake != 42) throw new IOException("Unexpected handshake " + handshake);

                logger.debug("Handshake received!");
            }

            chat.sendMessage(LNJTwitchBot.CHANNEL_NAME, "Streatham Hill Stories est connecté ! "
                    + "Commandes disponibles : " + String.join(", ", COMMANDS));

            new Thread("Command Poster") {
                @Override
                public void run() {
                    while (SHSChatControl.this.sessionId == sessionId) {
                        try {
                            Thread.sleep(300_000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        chat.sendMessage(LNJTwitchBot.CHANNEL_NAME, "Commandes disponibles : " + String.join(", ", COMMANDS));
                    }
                }
            }.start();

            while (true) {
                // we want at least to send a byte every 30 seconds (even if it is 0 - do nothing) as a keepalive.
                Byte command = commandQueue.poll(30, TimeUnit.SECONDS);
                if (command == null) command = 0;

                logger.debug("Sending command {}...", command);
                os.write(command);
                os.flush();

                int response = is.read();
                if (response != command) throw new IOException("Unexpected response " + response);
                logger.debug("Command acknowledged!");
            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
