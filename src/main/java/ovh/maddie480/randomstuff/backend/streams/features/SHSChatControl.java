package ovh.maddie480.randomstuff.backend.streams.features;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.streams.apis.AbstractChatProvider;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            "!zombie", "!clio", "!audi", "!transform", "!enter_bowling", "!enter_caesars",
            "!police", "!set_gravity", "!poop", "!snow", "!rain", "!upside_down", "!tiny", "!flip",
            "!radio_lnj", "!ultra_slow", "!ultra_fast", "!superman", "!fighter_jet", "!clipping_land",
            "!mirror_mode", "!sniper_view", "!tunnel_vision", "!murk", "!give_gun", "!give_grenade",
            "!give_flame", "!give_bat", "!exploding_peds", "!chirac_en_3d"
    );

    private final List<AbstractChatProvider<?>> chatProviders;

    private int sessionId = 0;

    private BlockingQueue<Byte> commandQueue;

    public SHSChatControl(List<AbstractChatProvider<?>> chatProviders) {
        this.chatProviders = chatProviders;
    }

    public void run() {
        runSHSServerSocket();
        runSHSRadioSocket();
    }

    private void runSHSServerSocket() {
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

    private void runSHSRadioSocket() {
        new Thread("SHS Radio Socket") {
            @Override
            public void run() {
                try (ServerSocket serverSocket = new ServerSocket(11585)) {
                    while (true) {
                        try (Socket socket = serverSocket.accept()) {
                            sendRadio(socket);
                        } catch (IOException | InterruptedException e) {
                            logger.error("Error while sending radio to client!", e);
                        }
                    }
                } catch (IOException e) {
                    logger.error("Error while starting radio socket!", e);
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

            chatProviders.forEach(provider -> provider.sendMessage("Streatham Hill Stories est connecté ! "
                    + "Commandes disponibles : " + String.join(", ", COMMANDS)));

            new Thread("Command Poster") {
                @Override
                public void run() {
                    while (SHSChatControl.this.sessionId == sessionId) {
                        try {
                            Thread.sleep(300_000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        chatProviders.forEach(provider -> provider.sendMessage("Commandes disponibles : " + String.join(", ", COMMANDS)));
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

    private void sendRadio(Socket socket) throws IOException, InterruptedException {
        try (InputStream is = socket.getInputStream();
             ObjectOutputStream os = new ObjectOutputStream(socket.getOutputStream())) {

            {
                // hi! - 43
                int handshake = is.read();
                if (handshake != 43) throw new IOException("Unexpected handshake " + handshake);

                logger.debug("Handshake received!");
            }

            // get radio state, make sure there is more than 15 seconds left or hold the line
            String songPath;
            int seek;
            int duration;
            long seekTimeMillis;
            while (true) {
                try (InputStream ris = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/radio-lnj/playlist.json")) {
                    JSONObject response = new JSONObject(IOUtils.toString(ris, StandardCharsets.UTF_8));
                    songPath = "https://maddie480.ovh" + response.getJSONArray("playlist").getJSONObject(0).getString("path");
                    seek = response.getInt("seek");
                    duration = response.getJSONArray("playlist").getJSONObject(0).getInt("duration");
                    seekTimeMillis = System.currentTimeMillis();

                    if (duration - seek < 15000) {
                        logger.info("Time left is {}, not enough to be worth it! Waiting for the next song.", duration - seek);
                        Thread.sleep(duration - seek + 1500);
                    } else {
                        break;
                    }
                }
            }

            // download the song, cut it if necessary
            Path radioToSend;
            {
                Path radioTemp = Paths.get("/tmp/radio.mp3");
                Path radioTempCut = Paths.get("/tmp/radio_cut.mp3");
                radioToSend = radioTemp;

                if (Files.exists(radioTempCut)) Files.delete(radioTempCut);

                logger.debug("Next song is {} (duration {}) with seek {}, downloading...", songPath, duration, seek);

                try (InputStream ris = ConnectionUtils.openStreamWithTimeout(songPath);
                     OutputStream ros = Files.newOutputStream(radioTemp)) {

                    IOUtils.copy(ris, ros);
                }

                seek += (int) (System.currentTimeMillis() - seekTimeMillis);
                seekTimeMillis = System.currentTimeMillis();
                logger.debug("Download complete, seek adjusted to {}", seek);

                if (seek > 10000) {
                    List<String> command = Arrays.asList("ffmpeg",
                            "-ss", Float.toString(seek / 1000f),
                            "-i", radioTemp.toAbsolutePath().toString(),
                            "-to", Float.toString(duration / 1000f),
                            "-c", "copy",
                            radioTempCut.toAbsolutePath().toString()
                    );
                    logger.debug("Cutting file... Command line: {}", command);

                    Process ffmpeg = new ProcessBuilder(command).start();

                    OutputStreamLogger.redirectAllOutput(logger, ffmpeg);
                    ffmpeg.waitFor();
                    if (ffmpeg.exitValue() != 0) {
                        throw new IOException("Could not convert file: return code " + ffmpeg.exitValue());
                    }

                    Files.delete(radioTemp);
                    radioToSend = radioTempCut;

                    seek += (int) (System.currentTimeMillis() - seekTimeMillis);
                    logger.debug("Cut complete, seek adjusted to {}", seek);
                }
            }

            int fileSize = (int) Files.size(radioToSend);
            logger.debug("Transferring file {} @ {} ({} bytes) and remaining duration {} to client", songPath, radioToSend.toAbsolutePath().toString(), fileSize, duration - seek);

            // transfer the size, the file, then the time left
            os.writeInt(fileSize);
            byte[] buf = new byte[4096];
            try (InputStream ris = Files.newInputStream(radioToSend)) {
                while (true) {
                    int read = ris.read(buf);
                    if (read == -1) break;
                    os.write(buf, 0, read);
                }
            }
            Files.delete(radioToSend);
            os.writeInt(duration - seek + 1000);

            // send a random number, the client should reply with that number to acknowledge they received everything
            byte random = (byte) (Math.random() * Byte.MAX_VALUE);
            os.writeByte(random);
            os.flush();
            if (is.read() != random) throw new IOException("Did not receive ack!");

            logger.debug("Transfer done! Closing connection.");
        }
    }
}
