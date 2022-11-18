package com.max480.randomstuff.backend.utils;

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

    // read a file
    public static InputStream getCloudStorageInputStream(String filename) {
        BlobId blobId = BlobId.of("max480-random-stuff.appspot.com", filename);
        return new ByteArrayInputStream(storage.readAllBytes(blobId));
    }

    // send a string as a private file
    public static void sendStringToCloudStorage(String contents, String name, String contentType) throws IOException {
        sendStringToCloudStorage(contents, name, contentType, false);
    }

    // send a string as a public file
    public static void sendStringToCloudStorage(String contents, String name, String contentType, boolean isPublic) throws IOException {
        sendStringToCloudStorage(contents, name, contentType, isPublic, null);
    }

    // send a string and grant access to it to someone
    public static void sendStringToCloudStorage(String contents, String name, String contentType, boolean isPublic, String grantAccessTo) throws IOException {
        Path path = Files.createTempFile("cloud-storage-send-", "");
        FileUtils.writeStringToFile(path.toFile(), contents, StandardCharsets.UTF_8);
        sendToCloudStorage(path.toAbsolutePath().toString(), name, contentType, isPublic, grantAccessTo);
        Files.delete(path);
    }

    // send a file as private
    public static void sendToCloudStorage(String file, String name, String contentType) throws IOException {
        sendToCloudStorage(file, name, contentType, false);
    }

    // send a file as public
    public static void sendToCloudStorage(String file, String name, String contentType, boolean isPublic) throws IOException {
        sendToCloudStorage(file, name, contentType, isPublic, null);
    }

    // send a file and grant access to it to someone
    public static void sendToCloudStorage(String file, String name, String contentType, boolean isPublic, String grantAccessTo) throws IOException {
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
        if (grantAccessTo != null) {
            storage.createAcl(blobId, Acl.of(new Acl.User(grantAccessTo), Acl.Role.READER));
        }
    }
}
