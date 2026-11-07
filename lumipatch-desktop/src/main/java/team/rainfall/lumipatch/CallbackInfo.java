package team.rainfall.lumipatch;

/** State shared by an Inject callback and its transformed target method. */
public class CallbackInfo {
    private final String name;
    private final boolean cancellable;
    private boolean cancelled;
    private Object returnValue;
    private boolean hasReturnValue;
    private Object[] locals;
    private final int[] localSizes;
    private final String[] localTypes;

    public CallbackInfo(String name, boolean cancellable) { this(name, cancellable, null, null, null); }

    public CallbackInfo(String name, boolean cancellable, Object[] locals) {
        this(name, cancellable, locals, null, null);
    }

    public CallbackInfo(String name, boolean cancellable, Object[] locals, int[] localSizes, String[] localTypes) {
        this.name = name;
        this.cancellable = cancellable;
        this.locals = locals;
        this.localSizes = localSizes;
        this.localTypes = localTypes;
    }

    public String getName() { return name; }
    public boolean isCancellable() { return cancellable; }
    public boolean isCancelled() { return cancelled; }

    public void cancel() {
        if (!cancellable) throw new IllegalStateException("Injection '" + name + "' is not cancellable");
        cancelled = true;
    }

    public void cancel(Object value) {
        if (!cancellable) throw new IllegalStateException("Injection '" + name + "' is not cancellable");
        returnValue = value;
        hasReturnValue = true;
        cancelled = true;
    }

    public Object getReturnValue() { return returnValue; }
    public boolean hasReturnValue() { return hasReturnValue; }

    public Object[] getLocals() { return locals; }
    public void setLocals(Object[] locals) { this.locals = locals; }
    public Object getLocal(int index) { return locals == null ? null : locals[index]; }
    public void setLocal(int index, Object value) {
        if (locals == null) throw new IllegalStateException("Local dumping is disabled");
        locals[index] = value;
    }

    /** Returns the JVM slot size of the LVT entry at the given slot, or zero when unknown. */
    public int getLocalSize(int index) {
        return localSizes == null || index < 0 || index >= localSizes.length ? 0 : localSizes[index];
    }

    /** Returns the fully qualified Java type name recorded in the LVT, or null when unknown. */
    public String getLocalType(int index) {
        return localTypes == null || index < 0 || index >= localTypes.length ? null : localTypes[index];
    }
}
