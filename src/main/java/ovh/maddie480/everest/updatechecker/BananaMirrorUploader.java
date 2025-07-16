package ovh.maddie480.everest.updatechecker;

import java.io.IOException;
import java.nio.file.Path;

public class BananaMirrorUploader {
    public static void uploadFile(Path file, String destinationFolder, String filename) throws IOException  {
        BananaMirror.makeSftpAction(destinationFolder,
                channel -> channel.put(file.toAbsolutePath().toString(), filename));
    }
}
