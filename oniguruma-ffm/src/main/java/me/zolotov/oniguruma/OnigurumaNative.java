package me.zolotov.oniguruma;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class OnigurumaNative {
    static final int ONIG_OPTION_NONE = 0;
    static final int ONIG_OPTION_CAPTURE_GROUP = 1 << 8;
    static final int ONIG_OPTION_NOT_BEGIN_STRING = 1 << 22;
    static final int ONIG_OPTION_NOT_BEGIN_POSITION = 1 << 24;

    static final int ONIG_MISMATCH = -1;
    private static final int ONIG_MAX_ERROR_MESSAGE_LEN = 90;

    // onig_initialize is called once per classloader on first OnigurumaNative construction.
    // We deliberately never call onig_end: oniguruma's globals leak until process exit, which
    // is fine for library/IDE use and avoids fragile cross-instance refcounting (especially
    // across plugin classloaders, where one classloader's onig_end would tear down a library
    // another classloader is still using).
    private static volatile boolean initialized;

    static final StructLayout REGION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("allocated"),
            ValueLayout.JAVA_INT.withName("num_regs"),
            ValueLayout.ADDRESS.withName("beg"),
            ValueLayout.ADDRESS.withName("end"),
            ValueLayout.ADDRESS.withName("history_root")
    );

    // OnigErrorInfo { OnigEncoding enc; UChar* par; UChar* par_end; }
    static final StructLayout ERROR_INFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("enc"),
            ValueLayout.ADDRESS.withName("par"),
            ValueLayout.ADDRESS.withName("par_end")
    );

    private static final long NUM_REGS_OFFSET =
            REGION_LAYOUT.byteOffset(PathElement.groupElement("num_regs"));
    private static final long BEG_OFFSET =
            REGION_LAYOUT.byteOffset(PathElement.groupElement("beg"));
    private static final long END_OFFSET =
            REGION_LAYOUT.byteOffset(PathElement.groupElement("end"));

    final MemorySegment utf8Encoding;
    final MemorySegment defaultSyntax;

    final MethodHandle onigNew;
    final MethodHandle onigFree;
    final MethodHandle onigSearch;
    final MethodHandle onigRegionNew;
    final MethodHandle onigRegionFree;
    final MethodHandle onigErrorCodeToStr;

    OnigurumaNative(SymbolLookup lookup) {
        var linker = Linker.nativeLinker();

        this.utf8Encoding = requireSymbol(lookup, "OnigEncodingUTF8");

        var syntaxVar = requireSymbol(lookup, "OnigDefaultSyntax")
                .reinterpret(ValueLayout.ADDRESS.byteSize());
        this.defaultSyntax = syntaxVar.get(ValueLayout.ADDRESS, 0);

        this.onigNew = linker.downcallHandle(
                requireSymbol(lookup, "onig_new"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS
                )
        );
        this.onigFree = linker.downcallHandle(
                requireSymbol(lookup, "onig_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
        this.onigSearch = linker.downcallHandle(
                requireSymbol(lookup, "onig_search"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT
                )
        );
        this.onigRegionNew = linker.downcallHandle(
                requireSymbol(lookup, "onig_region_new"),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
        );
        this.onigRegionFree = linker.downcallHandle(
                requireSymbol(lookup, "onig_region_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
        this.onigErrorCodeToStr = linker.downcallHandle(
                requireSymbol(lookup, "onig_error_code_to_str"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(2)
        );

        ensureInitialized(linker, lookup);
    }

    private void ensureInitialized(Linker linker, SymbolLookup lookup) {
        if (initialized) {
            return;
        }
        synchronized (OnigurumaNative.class) {
            if (initialized) {
                return;
            }
            var initHandle = linker.downcallHandle(
                    requireSymbol(lookup, "onig_initialize"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            try (var arena = Arena.ofConfined()) {
                var encodings = arena.allocate(ValueLayout.ADDRESS);
                encodings.set(ValueLayout.ADDRESS, 0, utf8Encoding);
                int result = (int) initHandle.invokeExact(encodings, 1);
                if (result != 0) {
                    throw new OnigurumaException("onig_initialize failed with code " + result);
                }
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new OnigurumaException("Failed to initialize Oniguruma", t);
            }
            initialized = true;
        }
    }

    int regionNumRegs(MemorySegment region) {
        return region.get(ValueLayout.JAVA_INT, NUM_REGS_OFFSET);
    }

    MemorySegment regionBeg(MemorySegment region) {
        return region.get(ValueLayout.ADDRESS, BEG_OFFSET);
    }

    MemorySegment regionEnd(MemorySegment region) {
        return region.get(ValueLayout.ADDRESS, END_OFFSET);
    }

    String errorMessage(int code, MemorySegment errorInfo) {
        try (var arena = Arena.ofConfined()) {
            // +1 for the NUL terminator that onig_error_code_to_str always writes
            var buf = arena.allocate(ONIG_MAX_ERROR_MESSAGE_LEN + 1);
            MemorySegment info = errorInfo != null ? errorInfo : MemorySegment.NULL;
            int len = (int) onigErrorCodeToStr.invokeExact(buf, code, info);
            if (len < 0) {
                return "oniguruma error " + code;
            }
            byte[] bytes = new byte[len];
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, bytes, 0, len);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new OnigurumaException("Failed to resolve Oniguruma error message for code " + code, t);
        }
    }

    static SymbolLookup loadSystemLibrary(Arena arena) {
        String libName = System.mapLibraryName("onig");
        try {
            return SymbolLookup.libraryLookup(libName, arena);
        } catch (IllegalArgumentException ignored) {
            // Fall through to candidate paths
        }
        for (String dir : candidateDirectories()) {
            Path candidate = Path.of(dir, libName);
            if (Files.exists(candidate)) {
                return SymbolLookup.libraryLookup(candidate, arena);
            }
        }
        throw new UnsatisfiedLinkError(
                "Could not locate Oniguruma shared library '" + libName + "'. " +
                        "Install libonig or provide an explicit path via Oniguruma.createFromFile(Path)."
        );
    }

    static SymbolLookup loadLibraryAt(Path path, Arena arena) {
        return SymbolLookup.libraryLookup(path, arena);
    }

    private static List<String> candidateDirectories() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("/opt/homebrew/lib", "/usr/local/lib", "/usr/lib");
        }
        if (os.contains("linux")) {
            return List.of(
                    "/usr/lib/x86_64-linux-gnu",
                    "/usr/lib/aarch64-linux-gnu",
                    "/usr/lib64",
                    "/usr/lib",
                    "/lib/x86_64-linux-gnu",
                    "/lib"
            );
        }
        return List.of();
    }

    private static MemorySegment requireSymbol(SymbolLookup lookup, String name) {
        return lookup.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol '" + name + "' not found in Oniguruma"));
    }

}
