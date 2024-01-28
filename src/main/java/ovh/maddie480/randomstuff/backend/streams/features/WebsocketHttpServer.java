package ovh.maddie480.randomstuff.backend.streams.features;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A simple HTTP server that answers a simple HTML page to any request thrown to it.
 * This is because we need a plain HTTP page to be allowed to use plain websockets...
 */
public class WebsocketHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(WebsocketHttpServer.class);

    public void start() {
        new Thread("Websocket HTTP Server") {
            @Override
            public void run() {
                try (ServerSocket serverSocket = new ServerSocket(11587)) {
                    while (true) {
                        try (Socket socket = serverSocket.accept()) {
                            logger.info("Client connected!");
                            dealWithClient(socket);
                            logger.info("Client disconnected normally!");

                        } catch (IOException e) {
                            logger.warn("Client disconnected!", e);
                        }
                    }
                } catch (IOException e) {
                    logger.error("Error while starting server socket!", e);
                }
            }
        }.start();
    }

    private void dealWithClient(Socket socket) throws IOException {
        try (InputStream is = socket.getInputStream();
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            // pretend to consume the request
            String request = "";
            while (!request.equals("\r\n\r\n")) {
                int result = is.read();
                if (result == -1) throw new IOException("Connection closed!");
                request += ((char) result);
                if (request.length() == 5) request = request.substring(1, 5);
            }

            // send the response
            writer.write("""
                    HTTP/1.1 200 OK
                    content-security-policy: default-src 'self' https://maddie480.ovh; connect-src 'self' https://maddie480.ovh ws:; img-src 'self' https://maddie480.ovh https://static-cdn.jtvnw.net; frame-ancestors 'none'; object-src 'none';
                    connection: close
                    content-type: text/html
                    content-length: 288

                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <title>LNJ Twitch Chat</title>
                        <link rel="stylesheet" href="https://maddie480.ovh/css/twitch-chat.css">
                    </head>
                    <body id="body">
                        <script src="https://maddie480.ovh/js/twitch-chat.js"></script>
                    </body>
                    </html>
                    """);
        }
    }
}
