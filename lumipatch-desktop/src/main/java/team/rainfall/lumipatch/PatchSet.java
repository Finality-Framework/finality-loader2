package team.rainfall.lumipatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

public final class PatchSet {
    private final List<String> mixins;

    private PatchSet(List<String> mixins) { this.mixins = mixins; }

    public static PatchSet read(ZipFile zip) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry("patch.json");
        if (entry == null) throw new IOException("Finality plugin is missing required patch.json");
        try (InputStream input = zip.getInputStream(entry)) {
            JsonNode root = new ObjectMapper().readTree(input);
            JsonNode values = root.isArray() ? root : root.get("mixins");
            if (values == null || !values.isArray()) throw new IOException("patch.json must be an array or contain a 'mixins' array");
            List<String> result = new ArrayList<String>();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().trim().isEmpty()) throw new IOException("patch.json contains an invalid Mixin class name");
                result.add(value.asText());
            }
            return new PatchSet(result);
        }
    }

    public List<String> getMixins() { return mixins; }
}
