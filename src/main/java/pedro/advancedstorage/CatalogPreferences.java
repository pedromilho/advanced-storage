package pedro.advancedstorage;

import com.google.gson.Gson;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Search and sorting are shared by both catalogs for the same player. */
final class CatalogPreferences {
    private static final Gson GSON = new Gson();
    private final Map<UUID, CatalogView> views = new ConcurrentHashMap<>();
    private final Path directory;
    private final HytaleLogger logger;

    CatalogPreferences(Path directory, HytaleLogger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    CatalogView get(UUID playerId) {
        return views.computeIfAbsent(playerId, this::read);
    }

    void remember(UUID playerId, CatalogView view) {
        views.compute(playerId, (id, current) -> {
            if (!view.equals(current)) save(id, view);
            return view;
        });
    }

    private CatalogView read(UUID playerId) {
        try {
            var view = GSON.fromJson(Files.readString(file(playerId)), CatalogView.class);
            return view == null ? CatalogView.DEFAULT : view;
        } catch (NoSuchFileException missing) {
            return CatalogView.DEFAULT;
        } catch (IOException | RuntimeException failure) {
            logger.atWarning().withCause(failure).log("Could not load Catalog preferences for %s", playerId);
            return CatalogView.DEFAULT;
        }
    }

    private void save(UUID playerId, CatalogView view) {
        var file = file(playerId);
        var pending = directory.resolve(playerId + ".pending");
        try {
            Files.createDirectories(directory);
            Files.writeString(pending, GSON.toJson(view));
            try {
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            logger.atWarning().withCause(failure).log("Could not save Catalog preferences for %s", playerId);
        }
    }

    private Path file(UUID playerId) {
        return directory.resolve(playerId + ".json");
    }
}
