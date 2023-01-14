package com.max480.randomstuff.backend.celeste;

import com.google.api.core.ApiService;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.storage.*;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.max480.randomstuff.backend.discord.modstructureverifier.FontGenerator;
import com.max480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * When a user asks for mod structure verifying or for font generating with BMFont on the website,
 * it delegates the task to the backend. To do that, the frontend writes the relevant file to Cloud Storage,
 * then sends a message on Pub/Sub... that lands here.
 * So, this class receives the message, does the stuff, and deposits the result on Cloud Storage.
 */
public class FrontendTaskReceiver {
    private static final Logger log = LoggerFactory.getLogger(FrontendTaskReceiver.class);
    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();

    private static ApiService pubsub;

    /**
     * Starts listening for pub/sub messages.
     */
    public static void start() {
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of("max480-random-stuff", "backend-subscriber");
        MessageReceiver receiver = FrontendTaskReceiver::messageReceived;
        pubsub = Subscriber.newBuilder(subscriptionName, receiver).build().startAsync();
    }

    /**
     * Stops listening for pub/sub messages.
     */
    public static void stop() {
        if (pubsub != null) {
            pubsub.stopAsync().awaitTerminated();
            pubsub = null;
        }
    }

    /**
     * Handles any incoming pub/sub messages.
     */
    private static void messageReceived(PubsubMessage message, AckReplyConsumer consumer) {
        consumer.ack();

        try {
            JSONObject o = new JSONObject(message.getData().toStringUtf8());

            switch (o.getString("taskType")) {
                case "modStructureVerify" -> {
                    boolean withPathsCheck = o.getBoolean("withPathsCheck");
                    handleModStructureVerifyRequest(
                            o.getString("fileName"),
                            withPathsCheck ? o.getString("mapFolderName") : null,
                            withPathsCheck ? o.getString("assetFolderName") : null);
                }
                case "fontGenerate" -> handleFontGenerateRequest(o.getString("fileName"), o.getString("language"));
                case "fileSearch" -> {
                    try {
                        ModFileSearcher.findAllModsByFile(
                                o.getString("search"),
                                o.getBoolean("exact")
                        );
                    } catch (IOException e) {
                        log.error("Error while searching file for request {}", o, e);
                    }
                }
                default -> log.error("Received invalid task type {}!", o.getString("taskType"));
            }
        } catch (JSONException e) {
            log.error("Received an invalid JSON payload!", e);
        }
    }

    /**
     * Handles requests to verify a mod using the Mod Structure Verifier.
     */
    private static void handleModStructureVerifyRequest(String fileName, String mapFolderName, String assetFolderName) {
        log.info("Frontend asked us to verify mod at {} with map folder name '{}' and assetFolderName '{}'!", fileName, mapFolderName, assetFolderName);

        try {
            String taskName = fileName.substring(0, fileName.lastIndexOf("."));
            downloadCloudStorageFileToTempFolderAndDeleteIt(fileName);
            ModStructureVerifier.analyzeZipFileFromFrontend(new File("/tmp/" + fileName), assetFolderName, mapFolderName,
                    (message, files) -> sendResponse(taskName, message, files));
        } catch (IOException | StorageException e) {
            log.error("Could not handle mod structure verification asked by frontend!", e);
        }
    }

    /**
     * Handles requests to generate a Celeste font using the Font Generator.
     */
    private static void handleFontGenerateRequest(String fileName, String language) {
        log.info("Frontend asked us to generate font from {} with language {}!", fileName, language);

        try {
            String taskName = fileName.substring(0, fileName.lastIndexOf("."));
            downloadCloudStorageFileToTempFolderAndDeleteIt(fileName);
            FontGenerator.generateFontFromFrontend(new File("/tmp/" + fileName), language,
                    (message, files) -> sendResponse(taskName, message, files));
        } catch (IOException | StorageException e) {
            log.error("Could not handle font generation asked by frontend!", e);
        }
    }

    /**
     * Downloads the file with the given name from the staging (temporary files) bucket to the /tmp folder with the same name,
     * then deletes it from Cloud Storage.
     */
    private static void downloadCloudStorageFileToTempFolderAndDeleteIt(String fileName) throws IOException, StorageException {
        log.debug("Downloading {} from Cloud Storage...", fileName);

        BlobId blobId = BlobId.of("staging.max480-random-stuff.appspot.com", fileName);
        try (ReadChannel reader = storage.reader(blobId); FileOutputStream writerStream = new FileOutputStream("/tmp/" + fileName); FileChannel writer = writerStream.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(4 * 1024);
            while (reader.read(buffer) > 0 || buffer.position() != 0) {
                buffer.flip();
                writer.write(buffer);
                buffer.compact();
            }
        }
        storage.delete(blobId);
    }

    private static void sendResponse(String taskName, String message, List<File> files) {
        try {
            JSONObject object = new JSONObject();
            object.put("taskName", taskName);
            object.put("responseText", message);

            // send attachments to Cloud Storage and refer to it in the JSON
            JSONArray attachments = new JSONArray();
            for (File f : files) {
                log.debug("Sending file {} to Cloud Storage", f.getAbsolutePath());
                String fileName = sendToCloudStorageAndGiveFileName(taskName, f);
                attachments.put(fileName);
            }
            object.put("attachments", attachments);

            // write the JSON and send it to Cloud Storage
            log.debug("Sending JSON to Cloud Storage as {}: {}", taskName + ".json", object);
            File json = new File("/tmp/" + taskName + ".json");
            FileUtils.writeStringToFile(json, object.toString(), StandardCharsets.UTF_8);
            sendToCloudStorageAndGiveFileName(taskName, json);
            FileUtils.forceDelete(json);
        } catch (IOException e) {
            log.error("Cannot send response to frontend request to Google Cloud Storage!", e);
        }
    }

    private static String sendToCloudStorageAndGiveFileName(String taskName, File file) throws IOException, StorageException {
        BlobId blobId = BlobId.of("staging.max480-random-stuff.appspot.com", taskName + "-" + file.getName());
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/octet-stream").build();

        try (FileInputStream readerStream = new FileInputStream(file); WriteChannel writer = storage.writer(blobInfo); FileChannel reader = readerStream.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(4 * 1024);
            while (reader.read(buffer) > 0 || buffer.position() != 0) {
                buffer.flip();
                writer.write(buffer);
                buffer.compact();
            }
        }

        return taskName + "-" + file.getName();
    }
}
