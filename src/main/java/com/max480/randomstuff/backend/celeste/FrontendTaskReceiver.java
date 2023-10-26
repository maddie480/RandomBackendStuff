package com.max480.randomstuff.backend.celeste;

import com.max480.randomstuff.backend.celeste.crontabs.UpdateCheckerTracker;
import com.max480.randomstuff.backend.discord.modstructureverifier.FontGenerator;
import com.max480.randomstuff.backend.discord.modstructureverifier.ModStructureVerifier;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * When a user asks for mod structure verifying or for font generating with BMFont on the website,
 * it delegates the task to the backend. To do that, the frontend writes the relevant file to Cloud Storage,
 * then sends a message on Pub/Sub... that lands here.
 * So, this class receives the message, does the stuff, and deposits the result on Cloud Storage.
 */
public class FrontendTaskReceiver {
    private static final Logger log = LoggerFactory.getLogger(FrontendTaskReceiver.class);

    /**
     * Starts listening for pub/sub messages.
     */
    public static void start() {
        new Thread("Frontend Task Receiver") {
            @Override
            public void run() {
                try (ServerSocket serverSocket = new ServerSocket(44480)) {
                    while (true) {
                        try (Socket connection = serverSocket.accept()) {
                            messageReceived(IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            log.warn("Error while handling socket message", e);
                        }
                    }
                } catch (IOException e) {
                    log.error("Error while starting server socket", e);
                }
            }
        }.start();
    }

    /**
     * Handles any incoming pub/sub messages.
     */
    private static void messageReceived(String requestBody) {
        log.info("Message received on socket! {}", requestBody);

        try {
            JSONObject o = new JSONObject(requestBody);

            switch (o.getString("taskType")) {
                case "modStructureVerify" -> {
                    boolean withPathsCheck = o.getBoolean("withPathsCheck");
                    handleModStructureVerifyRequest(
                            o.getString("fileName"),
                            withPathsCheck ? o.getString("mapFolderName") : null,
                            withPathsCheck ? o.getString("assetFolderName") : null);
                }
                case "fontGenerate" -> handleFontGenerateRequest(o.getString("fileName"), o.getString("language"));
                case "customFontGenerate" -> handleCustomFontGenerateRequest(o.getString("textFileName"),
                        o.getString("fontFileName"), o.getString("resultFontFileName"));
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
                case "updateModStructureVerifierMaps" -> handleUpdateModStructureVerifierMapsRequest();
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
            String grabbedFile = grabFileFromSharedStorage(fileName);
            ModStructureVerifier.analyzeZipFileFromFrontend(new File(grabbedFile), assetFolderName, mapFolderName,
                    (message, files) -> sendResponse(taskName, message, files));
        } catch (IOException e) {
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
            String grabbedFile = grabFileFromSharedStorage(fileName);
            FontGenerator.generateFontFromFrontend(new File(grabbedFile), language,
                    (message, files) -> sendResponse(taskName, message, files));
        } catch (IOException e) {
            log.error("Could not handle font generation asked by frontend!", e);
        }
    }

    /**
     * Handles requests to generate a Celeste custom font using the Font Generator.
     */
    private static void handleCustomFontGenerateRequest(String textFileName, String fontFileName, String resultFontFileName) {
        log.info("Frontend asked us to generate custom font {} from text {} and font {}!", resultFontFileName, textFileName, fontFileName);

        try {
            String taskName = textFileName.substring(0, textFileName.lastIndexOf("."));
            String textFile = grabFileFromSharedStorage(textFileName);
            String fontFile = grabFileFromSharedStorage(fontFileName);
            FontGenerator.generateCustomFontFromFrontend(new File(textFile), new File(fontFile), resultFontFileName,
                    (message, files) -> sendResponse(taskName, message, files));
        } catch (IOException e) {
            log.error("Could not handle custom font generation asked by frontend!", e);
        }
    }

    private static void handleUpdateModStructureVerifierMapsRequest() {
        log.info("Frontend asked us to update mod structure verifier maps!");

        try {
            UpdateCheckerTracker.updateModStructureVerifierMaps();
        } catch (IOException e) {
            log.error("Could not handle update mod structure verifier maps request!", e);
        }
    }

    /**
     * Moves a file from the shared storage (/shared/temp) and moves it to /tmp.
     */
    private static String grabFileFromSharedStorage(String fileName) throws IOException {
        String target = "/tmp/" + fileName.replace("/", "-");

        log.debug("Grabbing {} from shared storage...", fileName);
        Files.move(Paths.get("/shared/temp/" + fileName), Paths.get(target));
        return target;
    }

    private static void sendResponse(String taskName, String message, List<File> files) {
        try {
            JSONObject object = new JSONObject();
            object.put("taskName", taskName);
            object.put("responseText", message);

            // send attachments to shared storage and refer to it in the JSON
            JSONArray attachments = new JSONArray();
            for (File f : files) {
                log.debug("Sending file {} to shared storage", f.getAbsolutePath());
                String fileName = copyToStorageAndGiveFileName(taskName, f);
                attachments.put(fileName);
            }
            object.put("attachments", attachments);

            // write the JSON and send it to Cloud Storage
            log.debug("Sending JSON to Cloud Storage as {}: {}", taskName + ".json", object);
            File json = new File("/tmp/" + taskName.replace("/", "-") + ".json");
            FileUtils.writeStringToFile(json, object.toString(), StandardCharsets.UTF_8);
            copyToStorageAndGiveFileName(taskName, json);
        } catch (IOException e) {
            log.error("Cannot send response to frontend request to Google Cloud Storage!", e);
        }
    }

    private static String copyToStorageAndGiveFileName(String taskName, File file) throws IOException {
        Path target = Paths.get("/shared/temp/" + taskName + "-" + file.getName());
        Files.move(file.toPath(), target);
        return target.getFileName().toString();
    }
}
