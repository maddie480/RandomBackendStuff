package ovh.maddie480.randomstuff.backend.teamsix;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static ovh.maddie480.randomstuff.backend.teamsix.Team6Server.assignIdAndAdd;
import static ovh.maddie480.randomstuff.backend.teamsix.Team6Server.attemptClosing;

public class Team6Lobby {
    private static final Logger log = LoggerFactory.getLogger(Team6Lobby.class);

    private final String name;
    private final byte id;
    private final Map<Byte, Socket> server;
    private final Map<Byte, Socket> clients = new HashMap<>();

    public Team6Lobby(Socket server, String name, Function<Team6Lobby, Byte> idProvider, Consumer<Team6Lobby> onDeath) {
        this.server = ImmutableMap.of((byte) 0, server);
        this.name = name;
        this.id = idProvider.apply(this);

        log.info("SPAWN #{} (name: {}, ip: {})", id, name, server.getInetAddress());

        new Thread(() -> {
            try {
                tunnel(server, (byte) 0, clients);
            } catch (Exception e) {
                log.warn("ERROR #{}", id, e);
            } finally {
                log.info("CLOSE #{}", id);
                attemptClosing(server);
                synchronized (clients) {
                    for (Socket client : clients.values()) attemptClosing(client);
                }
                onDeath.accept(this);
            }
        }).start();
    }

    public void joinServer(Socket client) {
        byte clientId = assignIdAndAdd(client, clients);
        log.info("SPAWN #{} ${}", id, clientId);

        new Thread(() -> {
            try {
                tunnel(client, clientId, server);
            } catch (Exception e) {
                log.warn("ERROR #{} ${}", id, clientId, e);
            } finally {
                log.info("CLOSE #{} ${}", id, clientId);
                attemptClosing(client);
                synchronized (clients) {
                    clients.remove(clientId);
                }
                notifyServer(false, clientId);
            }
        }).start();

        notifyServer(true, clientId);
    }

    private void notifyServer(boolean join, byte clientId) {
        try {
            synchronized (server) {
                OutputStream os = server.get((byte) 0).getOutputStream();
                os.write(join ? 0 : 1);
                os.write(clientId);
                os.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not notify server of client join/leave", e);
        }
    }

    private void tunnel(Socket from, byte fromId, Map<Byte, Socket> to) throws Exception {
        // "sometimes I feel like reinventing TCP/IP you know"
        while (true) {
            // format: [recipient id, size, content...]
            byte recipient;
            byte[] bytes;
            { // read recipient id
                int recipientI = from.getInputStream().read();
                if (recipientI == -1) break;
                recipient = (byte) recipientI;
            }
            {
                // read size
                int size = from.getInputStream().read();
                if (size == -1) break;

                // prepare the outgoing packet with [sender id, size, content]
                bytes = new byte[size + 2];
                bytes[0] = fromId;
                bytes[1] = (byte) size;

                // read content
                int received = from.getInputStream().read(bytes, 2, size);
                if (received < size) {
                    throw new IOException("Expected " + size + " bytes, received" + received);
                }
            }

            synchronized (to) {
                if (!to.containsKey(recipient)) {
                    log.info("DROP  #{} ${}", id, recipient);
                    return;
                }

                // send out the packet to the recipient
                OutputStream os = to.get(recipient).getOutputStream();
                os.write(bytes);
                os.flush();
            }
        }
    }

    public String getName() {
        return name;
    }

    public byte getId() {
        return id;
    }

    public int getClientCount() {
        synchronized (clients) {
            return 1 + clients.size();
        }
    }
}
