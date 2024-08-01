package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.Main;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.util.*;
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

    public void update() throws IOException {
        log.debug("Building file list to submit...");
        JSONObject request = new JSONObject();
        request.put("mods", getMirroredMods());
        request.put("screenshots", getMirroredScreenshots());
        request.put("richPresenceIcons", getMirroredRichPresenceIcons());
        request.put("isModSearchDatabaseUpdate", true);
        request.put("timestamp", System.currentTimeMillis() / 1000);

        log.debug("Calling mirror update endpoint with {} mods, {} screenshots and {} Rich Presence icons",
                request.getJSONArray("mods").length(),
                request.getJSONArray("screenshots").length(),
                request.getJSONArray("richPresenceIcons").length());

        callMirrorUpdateEndpoint(request);
    }

    private Set<String> getMirroredMods() throws IOException {
        Map<String, Map<String, Object>> updaterDatabase;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest_update.yaml")) {
            updaterDatabase = YamlUtil.load(is);
        }
        return updaterDatabase.values().stream()
                .map(mod -> (String) mod.get(Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL"))
                .map(this::getFileName)
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredScreenshots() throws IOException {
        List<Map<String, List<String>>> updaterDatabase;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/mod_search_database.yaml")) {
            updaterDatabase = YamlUtil.load(is);
        }
        return updaterDatabase.stream()
                .map(mod -> mod.get("MirroredScreenshots"))
                .flatMap(List::stream)
                .map(this::getFileName)
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredRichPresenceIcons() throws IOException {
        JSONArray updaterDatabase;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://celestemodupdater.0x0a.de/rich-presence-icons/list.json")) {
            updaterDatabase = new JSONArray(new JSONTokener(is));
        }
        Set<String> fileList = new HashSet<>();
        for (Object o : updaterDatabase) {
            fileList.add(o + ".png");
        }
        return fileList;
    }

    private String getFileName(String url) {
        return url.substring(url.lastIndexOf("/") + 1);
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
        if (responseCode != 200) {
            throw new IOException("otobot mirror returned response code " + responseCode);
        }

        log.debug("Done!");
    }
}
