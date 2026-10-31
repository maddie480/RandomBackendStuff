package ovh.maddie480.randomstuff.backend.utils;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.representer.Representer;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.EverestYamlEntryRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A utility class to parse and dump YAML objects.
 */
public class YamlUtil {
    // Inspired by https://stackoverflow.com/a/58840215
    private static class EverestYamlConstructor extends Constructor {
        private boolean isRoot = true;

        public EverestYamlConstructor() {
            super(new LoaderOptions());
        }

        @Override
        protected Object constructObject(final Node node) {
            if (isRoot) {
                // this is how we define our object as being a List<EverestYamlEntryRecord>
                if (!(node instanceof SequenceNode)) {
                    throw new YAMLException("The root of an everest.yaml file must be a SequenceNode, found a " + node.getClass().getName() + " instead");
                }
                ((SequenceNode) node).setListType(EverestYamlEntryRecord.class);
                isRoot = false;
            }
            if (node.getType() == String.class && !(node instanceof ScalarNode)) {
                // SnakeYAML attempts to build Strings from non-ScalarNodes in weird ways (like assigning fields using reflection)
                throw new YAMLException("String fields must be ScalarNodes, found a " + node.getClass().getName() + " instead");
            }
            return super.constructObject(node);
        }


        public void reset() {
            isRoot = true;
        }
    }


    private static final Yaml yaml;
    private static final Yaml everestYaml;
    private static final EverestYamlConstructor everestYamlConstructor;

    static {
        LoaderOptions loaderOptions = new LoaderOptions();

        // use the block flow style rather than the default "block at the root level, flow on deeper levels" default.
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        // use SafeConstructor to avoid callers being able to construct arbitrary Java objects
        yaml = new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);

        // the EverestYamlConstructor returns a typed list of EverestYamlEntryRecords, to avoid
        // all quirks associated with "no = false", "my everest.yaml name is actually a list", etc
        everestYamlConstructor = new EverestYamlConstructor();
        everestYaml = new Yaml(everestYamlConstructor, new Representer(dumperOptions), dumperOptions, loaderOptions);
        // some people added non-existing fields to their yamls, like "Author", ignore those
        everestYamlConstructor.getPropertyUtils().setSkipMissingProperties(true);
    }

    /**
     * Loads YAML data from an input stream.
     */
    public static <T> T load(InputStream is) {
        synchronized (yaml) {
            return yaml.load(is);
        }
    }

    public static List<EverestYamlEntryRecord> loadEverestYaml(InputStream is) {
        synchronized (everestYaml) {
            List<EverestYamlEntryRecord> result = everestYaml.load(is);
            everestYamlConstructor.reset();
            return result;
        }
    }


    /**
     * Dumps YAML data to an output stream.
     */
    public static void dump(Object data, OutputStream os) throws IOException {
        synchronized (yaml) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                yaml.dump(data, writer);
            }
        }
    }
}
