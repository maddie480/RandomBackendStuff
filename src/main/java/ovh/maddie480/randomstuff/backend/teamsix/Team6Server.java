package ovh.maddie480.randomstuff.backend.teamsix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Team6Server {
    private static final Logger log = LoggerFactory.getLogger(Team6Server.class);

    private static final Map<Byte, Team6Lobby> lobbies = new HashMap<>();

    public static void main() throws IOException {
        try (ServerSocket sock = new ServerSocket(0xb00b)) { // Team 6 likes that ID so much y'know...
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
        // the password is [4, 8, 0], fittingly enough.
        // this is just here to throw out anyone trying to come in with some other protocol.
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
            // output: [lobby id, player count, name size, name...]
            // then lobby id = 0 to signify the end of the list.
            synchronized (lobbies) {
                for (Team6Lobby lobby : lobbies.values()) {
                    os.write(lobby.getId());
                    os.write(lobby.getClientCount());
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
            // input: [name size, name...]
            String name;
            {
                int nameSize = is.read();
                byte[] rawName = new byte[nameSize];
                int readBytes = is.read(rawName);
                if (readBytes != nameSize) {
                    throw new IOException("Expected " + nameSize + " bytes for the server name, got " + readBytes + " instead");
                }
                name = new String(rawName, StandardCharsets.UTF_8);
            }

            Team6Lobby lobby = new Team6Lobby(socket, name,
                    l -> assignIdAndAdd(l, lobbies),
                    l -> {
                        synchronized (lobbies) {
                            lobbies.remove(l.getId());
                        }
                    });

            synchronized (lobbies) {
                lobbies.put(lobby.getId(), lobby);
            }
            return false;
        } else {
            // join server with id
            // input: the server id
            // output: 1 if success, 0 if failure
            boolean success;
            synchronized (lobbies) {
                byte serverId = (byte) request;
                success = lobbies.containsKey(serverId);
                if (success) {
                    lobbies.get(serverId).joinServer(socket);
                }
            }

            socket.getOutputStream().write(success ? 1 : 0);
            socket.getOutputStream().flush();
            return !success;
        }
    }

    static void attemptClosing(Socket sock) {
        try {
            sock.close();
        } catch (IOException e) {
            // don't worry about it
        }
    }

    static <T> byte assignIdAndAdd(T toAdd, Map<Byte, T> existing) {
        synchronized (existing) {
            int id = 2;
            while (existing.containsKey((byte) id)) {
                id++;
            }
            byte b = (byte) id;

            existing.put(b, toAdd);
            return b;
        }
    }
}
