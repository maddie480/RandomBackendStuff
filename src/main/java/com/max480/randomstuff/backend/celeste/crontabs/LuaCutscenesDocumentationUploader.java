package com.max480.randomstuff.backend.celeste.crontabs;

import com.google.cloud.storage.*;
import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This service is called when Lua Cutscenes got updated, and mirrors the docs included in the mod zip
 * at https://storage.googleapis.com/lua-cutscenes-documentation/index.html.
 */
public class LuaCutscenesDocumentationUploader {
    private static final Logger logger = LoggerFactory.getLogger(LuaCutscenesDocumentationUploader.class);
    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();

    public static void updateLuaCutscenesDocumentation() throws IOException {
        // search for the Lua Cutscenes download URL in the mod updater database.
        // (we want the mirror so that we don't impact download count... and because it tends to be more stable.)
        String luaCutscenesDownloadUrl;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://max480-random-stuff.appspot.com/celeste/everest_update.yaml")) {
            Map<String, Map<String, Object>> db = YamlUtil.load(is);
            luaCutscenesDownloadUrl = db.get("LuaCutscenes").get("URL").toString();
        }

        // delete everything from the Cloud Storage bucket
        for (Blob blob : storage.list("lua-cutscenes-documentation").iterateAll()) {
            logger.info("Deleting file " + blob.getName() + " from Google Cloud Storage");
            storage.delete("lua-cutscenes-documentation", blob.getName());
        }

        // download Lua Cutscenes and go through its files
        logger.info("Downloading Lua Cutscenes from " + luaCutscenesDownloadUrl + "...");
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(luaCutscenesDownloadUrl);
        connection.setRequestProperty("User-Agent", "max480-random-stuff/1.0.0"); // the mirror hates Java 8 for some reason.
        try (ZipInputStream zip = new ZipInputStream(connection.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).startsWith("documentation/")) {
                    // that's part of the docs! we want to upload that.
                    BlobId blobId = BlobId.of("lua-cutscenes-documentation", entry.getName().substring(14));

                    // we don't need a super convoluted type guessing mechanism. we'll just take the file extension,
                    // as the Lua Cutscenes docs currently only contain html and css files.
                    String contentType = "application/octet-stream";
                    if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".css")) {
                        contentType = "text/css";
                    } else if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
                        contentType = "text/html";
                    }

                    // upload the blob to Google Cloud Storage.
                    logger.info("Uploading " + blobId.getName() + " (type " + contentType + ")");
                    BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();
                    storage.create(blobInfo, IOUtils.toByteArray(zip));
                    storage.createAcl(blobId, Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));
                }
            }
        }
    }
}
