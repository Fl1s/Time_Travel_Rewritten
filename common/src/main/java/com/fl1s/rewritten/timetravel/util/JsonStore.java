package com.fl1s.rewritten.timetravel.util;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic JSON read/write helper.
 * <p>
 * Writes go to a sibling {@code .tmp} file first and are then moved into place
 * with {@link StandardCopyOption#ATOMIC_MOVE} (falling back to a plain move if
 * the filesystem doesn't support atomic moves). This prevents a truncated
 * journal file if the process is killed mid-write.
 */
public final class JsonStore {
    private JsonStore() {
    }

    private static final Gson GSON = new GsonBuilder().create();

    public static <T> T load(Path file, Type type) {
        if (file == null || !Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type);
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to load JSON: {}", file, e);
            return null;
        }
    }

    public static boolean save(Path file, Object data, Type type) {
        if (file == null) return false;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, type, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            TimeTravel.LOOP_LOGGER.error("Failed to save JSON: {}", file, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
            return false;
        }
    }
}