package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A small script that is called daily in order to cache GameBanana API results for Arbitrary Mod App users.
 * It seems GameBanana is quite often not responsive, which makes calling those APIs in real time quite impractical...
 * <p>
 * Everything in the /shared/temp folder is removed 1 day after being added,
 * so no cleanup process is needed for mods that were removed from the Arbitrary Mod App.
 */
public class ArbitraryModAppCacher {
    private static final Logger logger = LoggerFactory.getLogger(ArbitraryModAppCacher.class);

    public static void refreshArbitraryModAppCache() throws IOException {
        JSONArray modList;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(
                "https://maddie480.ovh/gamebanana/arbitrary-mod-app-modlist?key=" + SecretConstants.RELOAD_SHARED_SECRET)) {

            modList = new JSONArray(new JSONTokener(is));
        }

        logger.debug("Got list of mods to cache: {}", modList);

        for (Object item : modList) {
            String modId = item.toString();
            logger.debug("Caching mod {}...", modId);

            byte[] modInfo = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/Mod/" + modId +
                        "?_csvProperties=_sProfileUrl,_sName,_aPreviewMedia,_tsDateAdded,_tsDateUpdated,_aGame,_aRootCategory,_aSubmitter,_bIsWithheld,_bIsTrashed,_bIsPrivate,_nViewCount,_nLikeCount,_nPostCount")) {

                    return IOUtils.toByteArray(is);
                }
            });

            Files.write(Paths.get("/shared/temp/arbitrary-mod-app-cache/" + modId + ".json"), modInfo);
        }

        logger.info("Caching done!");
    }
}
