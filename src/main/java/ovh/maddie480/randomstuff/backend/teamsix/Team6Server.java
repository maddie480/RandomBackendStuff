package ovh.maddie480.randomstuff.backend.teamsix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Team6Server {
    private static final Logger log = LoggerFactory.getLogger(Team6Server.class);

    private static final List<Team6Lobby> lobbies = new ArrayList<>();

    public static void main() throws IOException {
        try (ServerSocket sock = new ServerSocket(45067)) {
            while (true) {
                Socket client;
                try {
                    client = sock.accept();
                } catch (Exception e) {
                    log.warn("Connection accept failed", e);
                    continue;
                }

                try {
                    handleClient(client);
                } catch (Exception e) {
                    log.warn("Handshake with client failed", e);
                    attemptClosing(client);
                }
            }
        }
    }

    private static void handleClient(Socket socket) throws Exception {
        InputStream is = socket.getInputStream();
        if (is.read() != 4 || is.read() != 8 || is.read() != 0) {
            throw new IOException("The client didn't say the magic word");
        }

        new Thread(() -> {
            try {
                while (true) {
                    if (!handleClientRequest(socket)) break;
                }
            } catch (Exception e) {
                log.warn("Client died before joining a lobby!", e);
                attemptClosing(socket);
            }
        }).start();
    }

    private static boolean handleClientRequest(Socket socket) throws Exception {
        InputStream is = socket.getInputStream();
        OutputStream os = socket.getOutputStream();

        int request = is.read();
        if (request == -1) throw new IOException("Unexpected end of stream");

        if (request == 0) {
            // list servers
            synchronized (lobbies) {
                for (Team6Lobby lobby : lobbies) {
                    os.write(lobby.getId());
                    byte[] rawName = lobby.getName().getBytes(StandardCharsets.UTF_8);
                    os.write(rawName.length);
                    os.write(rawName);
                }
            }
            os.write(0);
            os.flush();
            return true;
        } else if (request == 1) {
            // create server
            int nameSize = is.read();
            byte[] rawName = new byte[nameSize];
            int readBytes = is.read(rawName);
            if (readBytes != nameSize) {
                throw new IOException("Expected " + nameSize + " bytes for the server name, got " + readBytes + " instead");
            }

            Team6Lobby lobby = new Team6Lobby(socket, new String(rawName, StandardCharsets.UTF_8), l -> {
                synchronized (lobbies) {
                    lobbies.remove(l);
                }
            });
            synchronized (lobbies) {
                lobbies.add(lobby);
            }
            return false;
        } else {
            // join server with id
            byte serverId = (byte) request;
            synchronized (lobbies) {
                for (Team6Lobby lobby : lobbies) {
                    if (lobby.getId() == serverId) {
                        os.write(1);
                        os.flush();
                        lobby.joinServer(socket);
                        return false;
                    }
                }
            }
            os.write(0);
            os.flush();
            return true;
        }
    }

    public static void attemptClosing(Socket sock) {
        try {
            sock.close();
        } catch (IOException e) {
            // don't worry about it
        }
    }
}
