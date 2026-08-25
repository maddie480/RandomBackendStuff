package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.OutputStream;
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

    public static OtobotMirror getInstance() throws IOException {
        if (instance == null) instance = new OtobotMirror();
        return instance;
    }

    private final Signature signature;

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

    public void update(ModDatabase database) throws IOException {
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

    private Set<String> getMirroredMods(ModDatabase database) throws IOException {
        return database.listLatestVersions().stream()
                .map(mod -> "https://celestemodupdater-storage.0x0a.de/" + mod.file().mirrorName + ".zip")
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredScreenshots(ModDatabase database) throws IOException {
        return database.allMods.stream()
                .map(mod -> Arrays.stream(mod.screenshots)
                        .filter(s -> s.mirrorName != null)
                        .toList())
                .flatMap(List::stream)
                .map(s -> "https://celestemodupdater-storage.0x0a.de/" + s.mirrorName + ".png")
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredRichPresenceIcons(ModDatabase database) throws IOException {
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
        connection.setReadTimeout(60000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", authorizationHeader);
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(bodyRaw);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("otobot mirror returned response code " + responseCode);
        }

        log.debug("Done!");
    }
}
