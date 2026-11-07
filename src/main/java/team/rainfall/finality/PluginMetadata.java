package team.rainfall.finality;

public final class PluginMetadata {
    public final String id;
    public final String name;
    public final String version;
    public final String mainClass;

    public PluginMetadata(String id, String name, String version, String mainClass) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.mainClass = mainClass;
    }
}
