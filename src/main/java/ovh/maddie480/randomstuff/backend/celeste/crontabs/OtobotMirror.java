package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.WebhookExecutor;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class OtobotMirror {
    private static final Logger log = LoggerFactory.getLogger(OtobotMirror.class);
    private static OtobotMirror instance;

    private static OtobotMirror getInstance() throws IOException {
        if (instance == null) instance = new OtobotMirror();
        return instance;
    }

    private final Signature signature;

    public static void run(ModDatabase database) throws IOException {
        getInstance().update(database);
    }

    private OtobotMirror() throws IOException {
        try {
            Security.addProvider(new BouncyCastleProvider());

            byte[] pkcs8EncodedBytes = Base64.getDecoder().decode(SecretConstants.OTOBOT_WEBHOOK_PRIVATE_KEY);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8EncodedBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = kf.generatePrivate(keySpec);

            signature = Signature.getInstance("SHA256withRSA/PSS");
            signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            signature.initSign(privateKey);
        } catch (InvalidAlgorithmParameterException | InvalidKeyException | NoSuchAlgorithmException |
                 InvalidKeySpecException e) {
            throw new IOException(e);
        }
    }

    private void update(ModDatabase database) throws IOException {
        log.debug("Building file list to submit...");
        JSONObject request = new JSONObject();
        request.put("mods", getMirroredMods(database));
        request.put("screenshots", getMirroredScreenshots(database));
        request.put("richPresenceIcons", getMirroredRichPresenceIcons(database));
        request.put("isModSearchDatabaseUpdate", true);
        request.put("timestamp", System.currentTimeMillis() / 1000);

        log.debug("Calling mirror update endpoint with {} mods, {} screenshots and {} Rich Presence icons",
                request.getJSONArray("mods").length(),
                request.getJSONArray("screenshots").length(),
                request.getJSONArray("richPresenceIcons").length());

        callMirrorUpdateEndpoint(request);
    }

    private Set<String> getMirroredMods(ModDatabase database) {
        return database.listLatestVersions().stream()
                .map(mod -> "https://celestemodupdater-storage.0x0a.de/banana-mirror/" + mod.file().mirrorName + ".zip")
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredScreenshots(ModDatabase database) {
        return database.allMods.stream()
                .map(mod -> Arrays.stream(mod.screenshots)
                        .filter(s -> s.mirrorName != null)
                        .toList())
                .flatMap(List::stream)
                .map(s -> "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/" + s.mirrorName + ".png")
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredRichPresenceIcons(ModDatabase database) {
        return database.allMods.stream()
                .map(mod -> Arrays.stream(mod.files)
                        .map(f -> Arrays.stream(f.richPresenceIcons)
                                .map(r -> r.xxHash)
                                .toList())
                        .flatMap(List::stream)
                        .toList())
                .flatMap(List::stream)
                .map(hash -> "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/" + hash + ".png")
                .collect(Collectors.toSet());
    }

    private void callMirrorUpdateEndpoint(JSONObject body) throws IOException {
        byte[] bodyRaw = body.toString().getBytes(StandardCharsets.UTF_8);

        String authorizationHeader;
        try {
            signature.update(bodyRaw);
            byte[] signatureResult = signature.sign();
            authorizationHeader = Base64.getEncoder().encodeToString(signatureResult);
        } catch (SignatureException e) {
            throw new IOException(e);
        }

        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout("https://celestemods.com/api/gamebanana-mirror/update-webhook");
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", authorizationHeader);
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(bodyRaw);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 202) {
            throw new IOException("otobot mirror returned response code " + responseCode);
        }

        new Thread(OtobotMirror::trackMirrorUpdate).start();
        log.debug("Done!");
    }

    private static void trackMirrorUpdate() {
        try {
            while (true) {
                Thread.sleep(10000);

                JSONObject status;
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://celestemods.com/api/gamebanana-mirror/update-webhook")) {
                    status = new JSONObject(new JSONTokener(is));
                }

                switch (status.getString("status")) {
                    case "Not Started":
                        executeWebhook(":warning: The otobot mirroring didn't start!");
                        return;
                    case "In Progress":
                        log.debug("The otobot mirror update is still in progress: {}", status.getString("message"));
                        break;
                    case "Succeeded":
                        log.debug("otobot mirror update done: {}", status.getString("message"));
                        executeWebhook(":white_check_mark: otobot mirror updated.");
                        return;
                    case "Failed":
                        executeWebhook(":x: otobot mirror update failed: " + status.getString("message"));
                        return;
                    default:
                        executeWebhook(":x: otobot mirror ended up in an unknown status: " + status.getString("status"));
                        return;
                }
            }
        } catch (Exception e) {
            log.error("otobot mirror status check thread died", e);
            executeWebhook(":x: otobot mirror status check thread died! " + e);
        }
    }

    private static void executeWebhook(String message) {
        try {
            WebhookExecutor.executeWebhook(
                    SecretConstants.UPDATE_CHECKER_LOGS_HOOK,
                    "Everest Update Checker",
                    "https://raw.githubusercontent.com/maddie480/RandomBackendStuff/main/webhook-avatars/update-checker.png",
                    message);
        } catch (IOException e) {
            log.info("Couldn't post webhook message, going on", e);
        }
    }

    public static String getWithSSE(String url) throws IOException {
        // I almost pulled in a library, but eh, server-sent events seem simple enough to parse for my purposes.
        // It's just an input stream, right?
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                ConnectionUtils.openStreamWithTimeout(url), StandardCharsets.UTF_8
        ))) {
            String s;
            while ((s = br.readLine()) != null) {
                if (!s.startsWith("data: ")) {
                    if (!s.trim().isEmpty()) log.debug("Ignored line: {}", s);
                    continue;
                }
                JSONObject message = new JSONObject(s.substring(6));

                switch (message.getString("status")) {
                    case "complete":
                        log.info("otobot sse is done!");
                        return message.getString("message");
                    case "error":
                        throw new IOException("otobot sse failed!\n```json\n" + message.toString(4) + "\n```");
                    default:
                        log.debug("Non-final message received: {}", message);
                }
            }

            throw new IOException("Unexpected end of otobot sse stream");
        }
    }
}
