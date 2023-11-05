package ovh.maddie480.randomstuff.backend.celeste;

import ovh.maddie480.everest.updatechecker.YamlUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModFileSearcher {
    public static void findAllModsByFile(String search, boolean exact) throws IOException {
        JSONArray result = new JSONArray();
        search = search.toLowerCase(Locale.ROOT);

        // load mod list
        List<String> mods;
        try (InputStream is = new FileInputStream("modfilesdatabase/list.yaml")) {
            mods = YamlUtil.load(is);
        }

        for (String mod : mods) {
            String itemtype = mod.substring(0, mod.indexOf("/"));
            int itemid = Integer.parseInt(mod.substring(mod.indexOf("/") + 1));

            // load file list for the mod
            List<String> files;
            try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/info.yaml")) {
                Map<String, Object> info = YamlUtil.load(is);
                files = (List<String>) info.get("Files");
            }

            for (String file : files) {
                // load file listing for the mod, so that we know which PNG files to check for
                List<String> fileList;
                try (InputStream is = new FileInputStream("modfilesdatabase/" + mod + "/" + file + ".yaml")) {
                    fileList = YamlUtil.load(is);
                }

                for (String path : fileList) {
                    path = path.toLowerCase(Locale.ROOT);
                    if ((exact && path.equals(search)) || (!exact && path.contains(search))) {
                        JSONObject item = new JSONObject();
                        item.put("itemtype", itemtype);
                        item.put("itemid", itemid);
                        item.put("fileid", Integer.parseInt(file));
                        result.put(item);
                        break;
                    }
                }
            }
        }

        Files.writeString(Paths.get("/shared/temp/file-searches/" + URLEncoder.encode(search, StandardCharsets.UTF_8) + "_" + exact + ".json"),
                result.toString(), StandardCharsets.UTF_8);
    }
}
