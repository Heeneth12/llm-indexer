package com.llm.indexer.core;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class Hashes {

    private final Path stateFile;
    private final Map<String, String> old = new HashMap<>();

    public Hashes(Path stateFile) {
        this.stateFile = stateFile;
        if (Files.exists(stateFile)) {
            try {
                for (String line : Files.readAllLines(stateFile)) {
                    int i = line.lastIndexOf('=');
                    if (i > 0) old.put(line.substring(0, i), line.substring(i + 1));
                }
            } catch (IOException ignored) {}
        }
    }

    public List<Path> changedFiles(List<Path> files) {
        List<Path> changed = new ArrayList<>();
        for (Path f : files)
            if (!hash(f).equals(old.get(f.toString()))) changed.add(f);
        return changed;
    }

    public void save(List<Path> files) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path f : files) sb.append(f).append('=').append(hash(f)).append('\n');
        Files.writeString(stateFile, sb.toString());
    }

    private String hash(Path f) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
