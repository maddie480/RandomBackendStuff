package com.max480.discord.randombots;

import com.google.cloud.storage.*;
import org.apache.commons.io.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CloudStorageUtils {
    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();

    public static InputStream getCloudStorageInputStream(String filename) {
        BlobId blobId = BlobId.of("max480-random-stuff.appspot.com", filename);
        return new ByteArrayInputStream(storage.readAllBytes(blobId));
    }

    public static void sendStringToCloudStorage(String contents, String name, String contentType, boolean isPublic) throws IOException {
        Path path = Files.createTempFile("cloud-storage-send-", "");
        FileUtils.writeStringToFile(path.toFile(), contents, StandardCharsets.UTF_8);
        sendToCloudStorage(path.toAbsolutePath().toString(), name, contentType, isPublic);
        Files.delete(path);
    }

    public static void sendToCloudStorage(String file, String name, String contentType, boolean isPublic) throws IOException {
        BlobId blobId = BlobId.of("max480-random-stuff.appspot.com", name);
        BlobInfo.Builder blobInfoBuilder = BlobInfo.newBuilder(blobId).setContentType(contentType);
        if (!isPublic) {
            // do not cache private stuff.
            blobInfoBuilder.setCacheControl("no-store");
        }
        storage.createFrom(blobInfoBuilder.build(), Paths.get(file), 4096);

        if (isPublic) {
            storage.createAcl(blobId, Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));
        }
    }
}
