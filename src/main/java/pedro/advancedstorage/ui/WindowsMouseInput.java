package pedro.advancedstorage.ui;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Receives mouse Raw Input in the Windows singleplayer server process. The
 * receiver never hooks the client's window and never consumes its input.
 */
public final class WindowsMouseInput implements AutoCloseable {
    public record Sample(int wheelDelta, boolean leftDown, boolean leftUp, boolean motion,
                         int cursorX, int cursorY, boolean focusLost) {}

    private static final System.Logger LOG = System.getLogger(WindowsMouseInput.class.getName());
    // Raw Input registrations are per process. Serialize teardown and rapid reopening.
    private static final ReentrantLock MOUSE_REGISTRATION = new ReentrantLock();
    private static final Sample FOCUS_LOST = new Sample(0, false, false, false, 0, 0, true);
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final MemorySegment NULL = MemorySegment.NULL;
    private static final int WM_INPUT = 0x00ff;
    private static final int PM_REMOVE = 1;
    private static final int RIDEV_REMOVE = 1;
    private static final int RIDEV_INPUTSINK = 0x0100;
    private static final int RID_INPUT = 0x10000003;
    private static final int RI_MOUSE_LEFT_BUTTON_DOWN = 0x0001;
    private static final int RI_MOUSE_LEFT_BUTTON_UP = 0x0002;
    private static final int RI_MOUSE_WHEEL = 0x0400;
    private static final int RAWINPUTDEVICE_SIZE = 16;
    private static final int RAWINPUTHEADER_SIZE = 24;
    private static final int RAWINPUT_MOUSE_SIZE = 48;
    private static final int MSG_SIZE = 48;
    private static final long MSG_MESSAGE = 8;
    private static final long MSG_WPARAM = 16;
    private static final long MSG_LPARAM = 24;
    private static final long MOUSE_BUTTON_FLAGS = 28;
    private static final long MOUSE_BUTTON_DATA = 30;
    private static final long MOUSE_LAST_X = 36;
    private static final long MOUSE_LAST_Y = 40;

    private final int clientPid;
    private final Consumer<Sample> listener;
    private final Thread receiver;
    private volatile boolean closed;

    private WindowsMouseInput(int clientPid, Consumer<Sample> listener) {
        this.clientPid = clientPid;
        this.listener = listener;
        receiver = Thread.ofPlatform().daemon().name("AdvancedStorage-WindowsMouseInput").unstarted(this::run);
    }

    public static WindowsMouseInput start(int clientPid, Consumer<Sample> listener) {
        if (clientPid <= 0) throw new IllegalArgumentException("The local client PID is required");
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                || ValueLayout.ADDRESS.byteSize() != 8) {
            throw new UnsupportedOperationException("Catalog Raw Input requires 64-bit Windows");
        }
        var input = new WindowsMouseInput(clientPid, Objects.requireNonNull(listener));
        input.receiver.start();
        return input;
    }

    @Override
    public void close() {
        closed = true;
        receiver.interrupt();
    }

    private void run() {
        try {
            MOUSE_REGISTRATION.lockInterruptibly();
            try (var arena = Arena.ofConfined()) {
                if (!closed) receive(arena, new User32(arena));
            } finally {
                MOUSE_REGISTRATION.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.WARNING, "Catalog mouse input is unavailable", failure);
            if (!closed) listener.accept(FOCUS_LOST);
        }
    }

    private void receive(Arena arena, User32 win) throws Throwable {
        var windowClass = arena.allocateFrom("STATIC", java.nio.charset.StandardCharsets.UTF_16LE);
        var window = (MemorySegment) win.createWindow.invokeExact(0, windowClass, NULL,
                0, 0, 0, 0, 0, MemorySegment.ofAddress(-3), NULL, NULL, NULL);
        if (window.address() == 0) throw new IllegalStateException("Could not create the Raw Input window");

        var message = arena.allocate(MSG_SIZE, 8);
        var registration = arena.allocate(RAWINPUTDEVICE_SIZE, 8);
        registration.set(SHORT, 0, (short) 1); // Generic desktop controls.
        registration.set(SHORT, 2, (short) 2); // Mouse.
        var count = arena.allocate(INT);
        var processId = arena.allocate(INT);
        var cursor = arena.allocate(8, 4);
        var raw = arena.allocate(RAWINPUT_MOUSE_SIZE, 8);
        var rawSize = arena.allocate(INT);
        boolean registered = false;
        try {
            while (!closed) {
                boolean focused = isClientForeground(win, processId);
                if (focused && !registered) {
                    // Do not overwrite another mouse registration in the server process.
                    if (registeredMouse(win, count) != 0) {
                        throw new IllegalStateException("Another server component already owns Raw Input");
                    }
                    register(win, registration, window, RIDEV_INPUTSINK);
                    registered = true;
                } else if (!focused && registered) {
                    unregister(win, registration, window, count);
                    registered = false;
                    if (!closed) listener.accept(FOCUS_LOST);
                }
                // Bound each pass so a high-polling mouse cannot starve focus checks or teardown.
                for (int i = 0; i < 256 && !closed
                        && (int) win.peekMessage.invokeExact(message, window, 0, 0, PM_REMOVE) != 0; i++) {
                    int kind = message.get(INT, MSG_MESSAGE);
                    if (kind == WM_INPUT) {
                        try {
                            if (registered && isClientForeground(win, processId)) {
                                readMouse(win, message.get(ValueLayout.ADDRESS, MSG_LPARAM), raw, rawSize, cursor);
                            }
                        } finally {
                            // WM_INPUT requires default processing to release foreground message data.
                            long ignored = (long) win.defWindowProc.invokeExact(window, kind,
                                    message.get(LONG, MSG_WPARAM), message.get(LONG, MSG_LPARAM));
                        }
                    } else {
                        long ignored = (long) win.dispatchMessage.invokeExact(message);
                    }
                }
                Thread.sleep(8);
            }
        } finally {
            try {
                if (registered) unregister(win, registration, window, count);
            } finally {
                int ignored = (int) win.destroyWindow.invokeExact(window);
            }
        }
    }

    private boolean isClientForeground(User32 win, MemorySegment processId) throws Throwable {
        var foreground = (MemorySegment) win.getForegroundWindow.invokeExact();
        return foreground.address() != 0
                && (int) win.getWindowThreadProcessId.invokeExact(foreground, processId) != 0
                && processId.get(INT, 0) == clientPid;
    }

    private static long registeredMouse(User32 win, MemorySegment count) throws Throwable {
        try (var scratch = Arena.ofConfined()) {
            count.set(INT, 0, 0);
            if ((int) win.getRegisteredDevices.invokeExact(NULL, count, RAWINPUTDEVICE_SIZE) == -1) {
                throw new IllegalStateException("Could not inspect existing Raw Input registrations");
            }
            int capacity = count.get(INT, 0);
            if (capacity == 0) return 0;
            var devices = scratch.allocate((long) capacity * RAWINPUTDEVICE_SIZE, 8);
            int size = (int) win.getRegisteredDevices.invokeExact(devices, count, RAWINPUTDEVICE_SIZE);
            if (size == -1) throw new IllegalStateException("Could not read existing Raw Input registrations");
            for (int i = 0; i < size; i++) {
                long offset = (long) i * RAWINPUTDEVICE_SIZE;
                if (devices.get(SHORT, offset) == 1 && devices.get(SHORT, offset + 2) == 2) {
                    long window = devices.get(ValueLayout.ADDRESS, offset + 8).address();
                    // An existing registration may use a null target to follow focus.
                    return window == 0 ? -1 : window;
                }
            }
            return 0;
        }
    }

    private static void register(User32 win, MemorySegment device, MemorySegment target, int flags) throws Throwable {
        device.set(INT, 4, flags);
        device.set(ValueLayout.ADDRESS, 8, target);
        if ((int) win.registerDevices.invokeExact(device, 1, RAWINPUTDEVICE_SIZE) == 0) {
            throw new IllegalStateException("Could not change the mouse Raw Input registration");
        }
    }

    private static void unregister(User32 win, MemorySegment device, MemorySegment window, MemorySegment count)
            throws Throwable {
        if (registeredMouse(win, count) == window.address()) register(win, device, NULL, RIDEV_REMOVE);
    }

    private void readMouse(User32 win, MemorySegment handle, MemorySegment raw, MemorySegment size,
                           MemorySegment cursor) throws Throwable {
        size.set(INT, 0, RAWINPUT_MOUSE_SIZE);
        int copied = (int) win.getRawInputData.invokeExact(handle, RID_INPUT, raw, size, RAWINPUTHEADER_SIZE);
        if (copied != RAWINPUT_MOUSE_SIZE || raw.get(INT, 0) != 0) return; // RIM_TYPEMOUSE.
        int flags = Short.toUnsignedInt(raw.get(SHORT, MOUSE_BUTTON_FLAGS));
        int wheel = (flags & RI_MOUSE_WHEEL) == 0 ? 0 : raw.get(SHORT, MOUSE_BUTTON_DATA);
        boolean down = (flags & RI_MOUSE_LEFT_BUTTON_DOWN) != 0;
        boolean up = (flags & RI_MOUSE_LEFT_BUTTON_UP) != 0;
        boolean motion = raw.get(INT, MOUSE_LAST_X) != 0 || raw.get(INT, MOUSE_LAST_Y) != 0;
        if ((wheel != 0 || down || up || motion) && !closed
                && (int) win.getPhysicalCursorPos.invokeExact(cursor) != 0) {
            listener.accept(new Sample(wheel, down, up, motion, cursor.get(INT, 0), cursor.get(INT, 4), false));
        }
    }

    private static final class User32 {
        private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;
        private final MethodHandle createWindow, destroyWindow, peekMessage, dispatchMessage, defWindowProc;
        private final MethodHandle getForegroundWindow, getWindowThreadProcessId, getPhysicalCursorPos;
        private final MethodHandle registerDevices, getRegisteredDevices, getRawInputData;

        User32(Arena arena) {
            var library = SymbolLookup.libraryLookup("user32.dll", arena);
            var linker = Linker.nativeLinker();
            createWindow = bind(linker, library, "CreateWindowExW",
                    FunctionDescriptor.of(PTR, INT, PTR, PTR, INT, INT, INT, INT, INT, PTR, PTR, PTR, PTR));
            destroyWindow = bind(linker, library, "DestroyWindow", FunctionDescriptor.of(INT, PTR));
            peekMessage = bind(linker, library, "PeekMessageW", FunctionDescriptor.of(INT, PTR, PTR, INT, INT, INT));
            dispatchMessage = bind(linker, library, "DispatchMessageW", FunctionDescriptor.of(LONG, PTR));
            defWindowProc = bind(linker, library, "DefWindowProcW", FunctionDescriptor.of(LONG, PTR, INT, LONG, LONG));
            getForegroundWindow = bind(linker, library, "GetForegroundWindow", FunctionDescriptor.of(PTR));
            getWindowThreadProcessId = bind(linker, library, "GetWindowThreadProcessId", FunctionDescriptor.of(INT, PTR, PTR));
            getPhysicalCursorPos = bind(linker, library, "GetPhysicalCursorPos", FunctionDescriptor.of(INT, PTR));
            registerDevices = bind(linker, library, "RegisterRawInputDevices", FunctionDescriptor.of(INT, PTR, INT, INT));
            getRegisteredDevices = bind(linker, library, "GetRegisteredRawInputDevices", FunctionDescriptor.of(INT, PTR, PTR, INT));
            getRawInputData = bind(linker, library, "GetRawInputData", FunctionDescriptor.of(INT, PTR, INT, PTR, PTR, INT));
        }

        private static MethodHandle bind(Linker linker, SymbolLookup library, String name, FunctionDescriptor type) {
            return linker.downcallHandle(library.findOrThrow(name), type);
        }
    }
}
