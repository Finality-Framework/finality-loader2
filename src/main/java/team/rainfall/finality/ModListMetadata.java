package team.rainfall.finality;

import java.util.ArrayList;

public final class ModListMetadata {
    public final boolean enabled;
    public final ArrayList<String> mods;

    public ModListMetadata(boolean enabled, ArrayList<String> mods) {
        this.enabled = enabled;
        this.mods = mods;
    }
}
