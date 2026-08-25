package ovh.maddie480.randomstuff.backend.celeste;

import org.json.JSONArray;
import org.json.JSONObject;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModDatabase;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.FileRecord;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.ModRecord;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public class ModFileSearcher {
    public static void findAllModsByFile(String search, boolean exact) throws IOException {
        JSONArray result = new JSONArray();
        search = search.toLowerCase(Locale.ROOT);

        try (ModDatabase database = new ModDatabase()) {
            for (ModRecord mod : database.allMods) {
                for (FileRecord file : mod.files) {
                    for (String path : file.fileListing) {
                        path = path.toLowerCase(Locale.ROOT);
                        if ((exact && path.equals(search)) || (!exact && path.contains(search))) {
                            JSONObject item = new JSONObject();
                            item.put("modid", mod.id);
                            item.put("fileid", file.id);
                            result.put(item);
                            break;
                        }
                    }
                }
            }
        }

        Files.writeString(Paths.get("/shared/temp/file-searches/" + URLEncoder.encode(search, StandardCharsets.UTF_8) + "_" + exact + ".json"),
                result.toString(), StandardCharsets.UTF_8);
    }
}
