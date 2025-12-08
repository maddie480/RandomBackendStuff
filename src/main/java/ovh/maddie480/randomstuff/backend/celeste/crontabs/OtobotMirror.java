package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
        try (InputStream is = Files.newInputStream(Paths.get("uploads/everestupdate.yaml"))) {
            updaterDatabase = YamlUtil.load(is);
        }
        return updaterDatabase.values().stream()
                .map(mod -> (String) mod.get("MirrorURL"))
                .map(mod -> "https://celestemodupdater-storage.0x0a.de/" + mod.substring("https://celestemodupdater.0x0a.de/".length()))
                .filter(mod -> !Arrays.asList(
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror/484636.zip",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror/1214662.zip",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror/604125.zip",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror/1574839.zip",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror/1410481.zip"
                ).contains(mod))
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredScreenshots() throws IOException {
        List<Map<String, List<String>>> modSearchDatabase;
        try (InputStream is = Files.newInputStream(Paths.get("uploads/modsearchdatabase.yaml"))) {
            modSearchDatabase = YamlUtil.load(is);
        }
        return modSearchDatabase.stream()
                .map(mod -> mod.get("MirroredScreenshots"))
                .flatMap(List::stream)
                .map(mod -> "https://celestemodupdater-storage.0x0a.de/" + mod.substring("https://celestemodupdater.0x0a.de/".length()))
                .filter(mod -> !Arrays.asList(
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_679706ef4a45a.png",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_66fca6179d61c.png",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_672c6e3909e08.png",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_683984624742f.png",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_6839845890023.png",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_68657c2a213ec.png",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_693150d334dd6.png",
                        "https://celestemodupdater-storage.0x0a.de/banana-mirror-images/img_ss_mods_693150d2ebe9b.png"
                ).contains(mod))
                .collect(Collectors.toSet());
    }

    private Set<String> getMirroredRichPresenceIcons() throws IOException {
        Map<String, Map<String, List<String>>> richPresenceIcons;
        try (InputStream is = Files.newInputStream(Paths.get("banana_mirror_rich_presence_icons.yaml"))) {
            richPresenceIcons = YamlUtil.load(is);
        }
        return richPresenceIcons.get("HashesToFiles").keySet().stream()
                .map(hash -> "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/" + hash + ".png")
                .filter(mod -> !Arrays.asList(
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/d8949733e3725149.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/b4c4a5e273e1e69c.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/5c5ad7b331e745f1.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/e910496c758d0d27.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/3297439aef239566.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/e2b43677fa1258cd.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/2c0783e7350faacb.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/979ab4946a710b8e.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/26fd23a14a37efa1.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/292cdfba118bccab.png",
                        "https://celestemodupdater-storage.0x0a.de/rich-presence-icons/234b8d317360cb74.png"
                ).contains(mod))
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
