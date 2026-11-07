package team.rainfall.finality;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataClassBuilderTest {
    @Test
    void generatesLoadablePluginAndModMetadataClasses() throws Exception {
        Map<String, byte[]> pool = new HashMap<String, byte[]>();
        ArrayList<PluginMetadata> plugins = new ArrayList<PluginMetadata>();
        plugins.add(new PluginMetadata("sample", "Sample", "1.2.3", null));
        MetadataClassBuilder.addTo(pool, plugins, true, java.util.Arrays.asList("/mods/sample"));

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = pool.get(name.replace('.', '/') + ".class");
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> pluginCollection = Class.forName(MetadataClassBuilder.PLUGINS_COLLECTION, true, loader);
        ArrayList<?> loadedPlugins = (ArrayList<?>) pluginCollection.getField("plugins").get(null);
        PluginMetadata plugin = (PluginMetadata) loadedPlugins.get(0);
        assertEquals("sample", plugin.id);
        assertEquals("1.2.3", plugin.version);

        Class<?> modListClass = Class.forName(MetadataClassBuilder.MOD_LIST, true, loader);
        ModListMetadata modList = (ModListMetadata) modListClass.getField("modList").get(null);
        assertTrue(modList.enabled);
        assertEquals(java.util.Collections.singletonList("/mods/sample"), modList.mods);
    }
}
