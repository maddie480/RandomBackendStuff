package com.max480.randomstuff.backend.celeste.crontabs;

import com.max480.everest.updatechecker.YamlUtil;
import com.max480.randomstuff.backend.SecretConstants;
import com.max480.randomstuff.backend.utils.ConnectionUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class AssetDriveService {
    private static final Logger log = LoggerFactory.getLogger(AssetDriveService.class);

    public static void refreshCachedAssets() throws IOException {
        JSONArray allFiles = listFilesInFolderRecursive(SecretConstants.ASSET_DRIVE_FOLDER_ID, new HashSet<>(Arrays.asList("image/png", "text/plain", "text/yaml")), "");
        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/temp/asset-drive/cached-list.json"))) {
            IOUtils.write(allFiles.toString(), os, StandardCharsets.UTF_8);
        }
    }

    public static void classifyAssets() throws IOException {
        JSONArray allFiles;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/temp/asset-drive/cached-list.json"))) {
            allFiles = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
        }

        JSONObject result = new JSONObject();
        result.put("misc", new JSONArray());
        result.put("decals", new JSONArray());
        result.put("stylegrounds", new JSONArray());
        result.put("bgtilesets", new JSONArray());
        result.put("fgtilesets", new JSONArray());

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
                assetName = folder.substring(folder.lastIndexOf("/") + 1) + "/" + file.getString("name");
            } else {
                author = folder;
                assetName = file.getString("name");
            }

            JSONObject mappedObject = new JSONObject();
            mappedObject.put("name", assetName);
            mappedObject.put("author", author);
            mappedObject.put("id", file.getString("id"));

            for (Object o2 : allFiles) {
                JSONObject readmeCandidate = (JSONObject) o2;

                if ("text/plain".equals(readmeCandidate.getString("mimeType"))
                        && file.getString("folder").startsWith(readmeCandidate.getString("folder"))) {

                    mappedObject.put("readme", readmeCandidate.getString("id"));
                    break;
                }
            }

            for (Object o2 : allFiles) {
                JSONObject yamlCandidate = (JSONObject) o2;

                if ("text/yaml".equals(yamlCandidate.getString("mimeType"))
                        && file.getString("folder").equals(yamlCandidate.getString("folder"))
                        && (file.getString("name") + ".yaml").equals(yamlCandidate.getString("name"))) {

                    Map<String, String> yaml;
                    try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://www.googleapis.com/drive/v3/files/" + file.getString("id") + "?key=" + SecretConstants.GOOGLE_DRIVE_API_KEY + "&alt=media")) {
                        yaml = YamlUtil.load(is);
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

        try (OutputStream os = Files.newOutputStream(Paths.get("/shared/temp/asset-drive/categorized-assets.json"))) {
            IOUtils.write(result.toString(), os, StandardCharsets.UTF_8);
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
        String url = "https://www.googleapis.com/drive/v3/files?key=" + SecretConstants.GOOGLE_DRIVE_API_KEY + "&q="
                + URLEncoder.encode("'" + folderId + "' in parents and trashed = false", StandardCharsets.UTF_8)
                + (pageToken == null ? "" : "&pageToken=" + pageToken);

        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url)) {
            return new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8));
        }
    }
}
