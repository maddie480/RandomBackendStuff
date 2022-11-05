package com.max480.discord.randombots;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A small script that is called daily in order to cache GameBanana API results for Arbitrary Mod App users.
 * It seems GameBanana is quite often not responsive, which makes calling those APIs in real time quite impractical...
 * <p>
 * Everything on the staging.max480-random-stuff.appspot.com bucket is removed 1 day after being added,
 * so no cleanup process is needed for mods that were removed from the Arbitrary Mod App.
 */
public class ArbitraryModAppCacher {
    private static final Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();
    private static final Logger logger = LoggerFactory.getLogger(ArbitraryModAppCacher.class);

    public static void refreshArbitraryModAppCache() throws IOException {
        JSONArray modList;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(
                "https://max480-random-stuff.appspot.com/gamebanana/arbitrary-mod-app-modlist?key=" + SecretConstants.RELOAD_SHARED_SECRET)) {

            modList = new JSONArray(IOUtils.toString(is, StandardCharsets.UTF_8));
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

            BlobId blobId = BlobId.of("staging.max480-random-stuff.appspot.com", "arbitrary-mod-app-cache/" + modId + ".json");
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("application/json")
                    .setCacheControl("no-store")
                    .build();
            storage.create(blobInfo, modInfo);
        }

        logger.info("Caching done!");
    }
}
