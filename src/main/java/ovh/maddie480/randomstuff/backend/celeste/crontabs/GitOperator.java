package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class GitOperator {
    private static final Logger log = LoggerFactory.getLogger(GitOperator.class);

    private static final Path gitDirectory = Paths.get("/tmp/Everest");
    private static Git gitRepository;

    public static void sshInit() {
        log.info("Configuring SSH...");

        SshSessionFactory.setInstance(new JschConfigSessionFactory() {
            @Override
            protected void configureJSch(JSch jsch) {
                try {
                    jsch.addIdentity(
                            "id_rsa",
                            SecretConstants.GITHUB_SSH_PRIVATE_KEY.getBytes(StandardCharsets.UTF_8),
                            SecretConstants.GITHUB_SSH_PUBLIC_KEY.getBytes(StandardCharsets.UTF_8),
                            null
                    );

                    ByteArrayInputStream is = new ByteArrayInputStream(SecretConstants.GITHUB_SSH_KNOWN_HOSTS.getBytes(StandardCharsets.UTF_8));
                    jsch.setKnownHosts(is);
                } catch (JSchException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public static void init() throws IOException {
        try {
            log.info("Cloning git repository...");
            gitRepository = Git.cloneRepository()
                    .setDirectory(gitDirectory.toFile())
                    .setBranch("dev")
                    .setDepth(1)
                    .setURI("git@github.com:EverestAPI/Everest.git")
                    .call();

            gitRepository.remoteAdd()
                    .setName("mine")
                    .setUri(new URIish("git@github.com:maddie480-bot/Everest.git"))
                    .call();

            gitRepository.push()
                    .setForce(true)
                    .setRemote("mine")
                    .call();
        } catch (GitAPIException | URISyntaxException e) {
            throw new IOException(e);
        }
    }

    public static void commitChanges() throws IOException {
        try {
            log.info("Adding");
            gitRepository.add()
                    .addFilepattern(".github")
                    .call();

            log.info("Committing");
            gitRepository.commit()
                    .setAll(true)
                    .setAuthor("Maddie-Bot", "212421949+maddie480-bot@users.noreply.github.com")
                    .setMessage("Bump TAS Check dependencies")
                    .call();

            log.info("Pushing");
            gitRepository.push().setRemote("mine").call();
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }
}
