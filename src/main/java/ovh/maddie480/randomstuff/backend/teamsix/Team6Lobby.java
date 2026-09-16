package ovh.maddie480.randomstuff.backend.teamsix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static ovh.maddie480.randomstuff.backend.teamsix.Team6Server.attemptClosing;

public class Team6Lobby {
    private static final Logger log = LoggerFactory.getLogger(Team6Lobby.class);

    private static final Object idLock = new Object();
    private static int nextId = 2;

    private final Socket server;
    private final String name;
    private final int id;
    private final List<Socket> clients = new ArrayList<>();

    public Team6Lobby(Socket server, String name, Consumer<Team6Lobby> onDeath) {
        this.server = server;
        this.name = name;

        synchronized (idLock) {
            this.id = nextId++;
            if (nextId == 256) nextId = 2;
        }

        log.info("Opening a new server with id #{} (name: {}) for client {}", id, name, server.getInetAddress());

        new Thread(() -> {
            try {
                tunnel(server, clients);
            } catch (Exception e) {
                log.warn("Server #{} died", id, e);
            } finally {
                log.info("Tearing down server #{}", id);
                attemptClosing(server);
                synchronized (clients) {
                    for (Socket client : clients) attemptClosing(client);
                }
                onDeath.accept(this);
            }
        }).start();
    }

    public void joinServer(Socket client) {
        log.info("Client {} is joining server #{}", client.getInetAddress(), id);

        synchronized (clients) {
            clients.add(client);
        }

        new Thread(() -> {
            try {
                tunnel(client, Collections.singletonList(server));
            } catch (Exception e) {
                log.warn("Client {} (on server #{}) died", client.getInetAddress(), id, e);
            } finally {
                log.info("Client {} leaves server #{}", client.getInetAddress(), id);
                attemptClosing(client);
                synchronized (clients) {
                    clients.remove(client);
                }
            }
        }).start();
    }

    private void tunnel(Socket from, List<Socket> to) throws Exception {
        while (true) {
            byte[] bytes;
            {
                int size = from.getInputStream().read();
                if (size == -1) break;

                bytes = new byte[size + 1];
                bytes[0] = (byte) size;
                int received = from.getInputStream().read(bytes, 1, size);
                if (received < size) {
                    throw new IOException("Expected " + size + " bytes, received" + received);
                }
            }

            synchronized (to) {
                List<Socket> deadSockets = new ArrayList<>();
                for (Socket singleTo : to) {
                    try {
                        singleTo.getOutputStream().write(bytes);
                        singleTo.getOutputStream().flush();
                    } catch (Exception e) {
                        log.warn("Client {} (on server #{}) died", singleTo.getInetAddress(), id, e);
                        attemptClosing(singleTo);
                        deadSockets.add(singleTo);
                    }
                }
                to.removeAll(deadSockets);
            }
        }
    }

    public String getName() {
        return name;
    }

    public int getId() {
        return id;
    }

    public int getClientCount() {
        synchronized (clients) {
            return 1 + clients.size();
        }
    }
}
