package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.auth.oauth2.GoogleCredentials;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AssetDriveService {
    private static final Logger log = LoggerFactory.getLogger(AssetDriveService.class);

    public static void listAllFiles() throws IOException {
        JSONArray allFiles = listFilesInFolderRecursive(SecretConstants.ASSET_DRIVE_FOLDER_ID, new HashSet<>(Arrays.asList(
                "image/png", "text/plain", "text/yaml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" /* <- docx */)), "");
        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/asset-drive/file-list.json"))) {
            IOUtils.write(allFiles.toString(), os, StandardCharsets.UTF_8);
        }

        log.debug("Calling frontend to refresh existing assets list...");
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/asset-drive/reload?key=" + SecretConstants.RELOAD_SHARED_SECRET)) {
            IOUtils.consume(is);
        }
    }

    public static void classifyAssets() throws IOException {
        JSONArray allFiles;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/asset-drive/file-list.json"))) {
            allFiles = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
        }

        Map<String, String> readmesPerFolder = new HashMap<>();
        for (Object o : allFiles) {
            JSONObject file = (JSONObject) o;
            if (Arrays.asList("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(file.getString("mimeType"))) {
                readmesPerFolder.put(file.getString("folder"), file.getString("id"));
            }
        }
        log.debug("README file IDs per folder: {}", readmesPerFolder);

        Map<String, String> indexYamlsPerFolder = new HashMap<>();
        for (Object o : allFiles) {
            JSONObject file = (JSONObject) o;
            if ("text/yaml".equals(file.getString("mimeType")) && "index.yaml".equals(file.getString("name"))) {
                indexYamlsPerFolder.put(file.getString("folder"), file.getString("id"));
            }
        }
        log.debug("index.yaml file IDs per folder: {}", indexYamlsPerFolder);

        JSONObject result = new JSONObject();
        result.put("misc", new JSONArray());
        result.put("decals", new JSONArray());
        result.put("stylegrounds", new JSONArray());
        result.put("bgtilesets", new JSONArray());
        result.put("fgtilesets", new JSONArray());
        result.put("hires", new JSONArray());

        for (Object o : allFiles) {
            JSONObject file = (JSONObject) o;
            if (!"image/png".equals(file.getString("mimeType"))) continue;

            String folder = file.getString("folder");
            String category;
            if (folder.startsWith("/Decals/")) {
                category = "decals";
                folder = folder.substring(8);
            } else if (folder.startsWith("/Stylegrounds/")) {
                category = "stylegrounds";
                folder = folder.substring(14);
            } else if (folder.startsWith("/Hi-Res Art/")) {
                category = "hires";
                folder = folder.substring(12);
            } else if (folder.startsWith("/Tilesets/Foreground Tilesets/")) {
                category = "fgtilesets";
                folder = folder.substring(30);
            } else if (folder.startsWith("/Tilesets/Background Tilesets/")) {
                category = "bgtilesets";
                folder = folder.substring(30);
            } else if (folder.startsWith("/Misc/")) {
                category = "misc";
                folder = folder.substring(6);
            } else {
                continue;
            }

            String author, assetName;
            if (folder.contains("/")) {
                author = folder.substring(0, folder.indexOf("/"));
                assetName = folder.substring(folder.indexOf("/") + 1) + "/" + file.getString("name");
            } else {
                author = folder;
                assetName = file.getString("name");
            }

            JSONObject mappedObject = new JSONObject();
            mappedObject.put("name", assetName);
            mappedObject.put("author", author);
            mappedObject.put("id", file.getString("id"));

            // find a README that would be in any parent folder.
            for (Map.Entry<String, String> readmeCandidate : readmesPerFolder.entrySet()) {
                if (file.getString("folder").startsWith(readmeCandidate.getKey())) {
                    mappedObject.put("readme", readmeCandidate.getValue());
                    break;
                }
            }

            // find a properties yaml file that is in the same folder, called "index.yaml".
            if (indexYamlsPerFolder.containsKey(file.getString("folder"))) {
                String[] yamls;
                try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/asset-drive/files/" + indexYamlsPerFolder.get(file.getString("folder")) + ".yaml"))) {
                    yamls = IOUtils.toString(is, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n---\n");
                }

                for (String yamlRaw : yamls) {
                    Map<String, String> yaml;
                    try (InputStream is = new ByteArrayInputStream(yamlRaw.getBytes(StandardCharsets.UTF_8))) {
                        yaml = YamlUtil.load(is);
                    }

                    String matchingPath = yaml.get("Path");
                    if (matchingPath.endsWith("*")) {
                        // prefix match
                        if (!file.getString("name").startsWith(matchingPath.substring(0, matchingPath.length() - 1))) {
                            continue;
                        }
                    } else {
                        // full name match
                        if (!file.getString("name").equals(matchingPath)) {
                            continue;
                        }
                    }

                    if (yaml.containsKey("Tags")) {
                        mappedObject.put("tags", Arrays.stream(yaml.get("Tags").split(","))
                                .map(String::trim).collect(Collectors.toList()));
                    }
                    if (yaml.containsKey("Name")) {
                        mappedObject.put("name", yaml.get("Name"));
                    }
                    if (yaml.containsKey("Author")) {
                        mappedObject.put("author", yaml.get("Author"));
                    }
                    if (yaml.containsKey("Template")) {
                        mappedObject.put("template", yaml.get("Template"));
                    }
                    if (yaml.containsKey("Notes")) {
                        mappedObject.put("notes", yaml.get("Notes"));
                    }

                    break;
                }
            }

            log.debug("Mapped {} to category {}: {}", file, category, mappedObject);

            result.getJSONArray(category).put(mappedObject);
        }

        for (Map.Entry<String, Object> entry : result.toMap().entrySet()) {
            List<Object> value = (List<Object>) entry.getValue();
            value.sort(Comparator.comparing(a -> ((Map<String, Object>) a).get("name").toString().toLowerCase(Locale.ROOT)));
            result.put(entry.getKey(), new JSONArray(value));
        }

        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/celeste/asset-drive/categorized-assets.json"))) {
            IOUtils.write(result.toString(), os, StandardCharsets.UTF_8);
        }
    }

    public static void rsyncFiles() throws IOException {
        Path syncedFilesRepository = Paths.get("/shared/celeste/asset-drive/files");

        GoogleCredentials credential = GoogleCredentials.fromStream(new ByteArrayInputStream(SecretConstants.GOOGLE_DRIVE_OAUTH_CONFIG.getBytes(StandardCharsets.UTF_8)))
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/drive.readonly"));

        Set<String> missingFiles;
        try (Stream<Path> fileListing = Files.list(syncedFilesRepository)) {
            missingFiles = fileListing
                    .map(file -> file.getFileName().toString())
                    .collect(Collectors.toSet());
        }

        JSONArray allFiles;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/asset-drive/file-list.json"))) {
            allFiles = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
        }

        for (Object o : allFiles) {
            String fileId = ((JSONObject) o).getString("id");
            String extension = switch (((JSONObject) o).getString("mimeType")) {
                case "image/png" -> "png";
                case "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "txt";
                case "text/yaml" -> "yaml";
                default -> "bin";
            };
            Instant lastModified = ZonedDateTime.parse(((JSONObject) o).getString("modifiedTime")).toInstant();

            Path cached = syncedFilesRepository.resolve(fileId + "." + extension);

            // 1. the file still exists, don't delete it
            missingFiles.remove(cached.getFileName().toString());

            // 2. if the last modified date matches, the file didn't get modified, so don't download it again
            if (Files.exists(cached) && Files.getLastModifiedTime(cached).toInstant().equals(lastModified)) {
                continue;
            }

            // 3. if it doesn't, download it and make sure we set the last modified time right!
            ConnectionUtils.runWithRetry(() -> {
                log.debug("Downloading Google Drive file with id {}, last modified on {}", fileId, lastModified);

                credential.refreshIfExpired();

                String url = "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media";
                if (((JSONObject) o).getString("mimeType").equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                    // DOCX files should be exported to TXT instead
                    url = "https://www.googleapis.com/drive/v3/files/" + fileId + "/export?mimeType=" + URLEncoder.encode("text/plain", StandardCharsets.UTF_8);
                }

                HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout(url);
                conn.setRequestProperty("Authorization", "Bearer " + credential.getAccessToken().getTokenValue());

                try (InputStream is = ConnectionUtils.connectionToInputStream(conn);
                     OutputStream os = Files.newOutputStream(cached)) {

                    IOUtils.copy(is, os);
                }

                Files.setLastModifiedTime(cached, FileTime.from(lastModified));
                return null;
            });
        }

        // 4. delete the files that don't exist anymore!
        for (String s : missingFiles) {
            log.warn("Deleting file {} that doesn't seem to exist anymore!", s);
            Files.delete(syncedFilesRepository.resolve(s));
        }
    }

    private static JSONArray listFilesInFolderRecursive(String folderId, Set<String> allowedMimeTypes, String folderPath) throws IOException {
        log.debug("Recursive walking: current folder is {} ({})", folderPath, folderId);
        JSONArray fileList = listFilesInFolder(folderId);

        // get files in this folder that match the MIME type
        JSONArray allFiles = new JSONArray();
        for (Object o : fileList) {
            JSONObject file = (JSONObject) o;
            if (allowedMimeTypes.contains(file.getString("mimeType"))) {
                allFiles.put(o);
                file.put("folder", folderPath);
            }
        }

        // get subfolders and query them
        for (Object o : fileList) {
            JSONObject subfolder = (JSONObject) o;
            if (!subfolder.getString("mimeType").equals("application/vnd.google-apps.folder")) {
                continue;
            }

            JSONArray filesInSubfolder = listFilesInFolderRecursive(subfolder.getString("id"), allowedMimeTypes, folderPath + "/" + subfolder.getString("name"));
            for (Object o2 : filesInSubfolder) {
                allFiles.put(o2);
            }
        }

        return allFiles;
    }

    private static JSONArray listFilesInFolder(String folderId) throws IOException {
        JSONObject response = listPageOfFilesInFolder(folderId, null);
        JSONArray fileList = response.getJSONArray("files");

        // get subsequent pages as necessary
        while (response.has("nextPageToken")) {
            response = listPageOfFilesInFolder(folderId, response.getString("nextPageToken"));
            for (Object o : response.getJSONArray("files")) {
                fileList.put(o);
            }
        }

        return fileList;
    }

    private static JSONObject listPageOfFilesInFolder(String folderId, String pageToken) throws IOException {
        String url = "https://www.googleapis.com/drive/v3/files?key=" + SecretConstants.GOOGLE_DRIVE_API_KEY
                + "&q=" + URLEncoder.encode("'" + folderId + "' in parents and trashed = false", StandardCharsets.UTF_8)
                + "&fields=" + URLEncoder.encode("files(id,mimeType,name,modifiedTime)", StandardCharsets.UTF_8)
                + (pageToken == null ? "" : "&pageToken=" + pageToken);

        JSONObject result;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
            result = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
        }

        for (Object o : result.getJSONArray("files")) {
            JSONObject file = (JSONObject) o;

            // if the file isn't a folder, guess its type based on the extension,
            // as autodetect can be unreliable when it comes to yaml and txt.
            if (!file.getString("mimeType").equals("application/vnd.google-apps.folder")) {
                String extension = file.getString("name");
                extension = extension.substring(extension.lastIndexOf(".") + 1);

                file.put("mimeType", switch (extension) {
                    case "yaml" -> "text/yaml";
                    case "txt" -> "text/plain";
                    default -> file.getString("mimeType");
                });
            }
        }

        return result;
    }
}
