package com.llm.indexer.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Shallow-clones a git repo into a caller-supplied directory. Only http(s) URLs
 * pointing at what looks like a public host are accepted, closing off the
 * obvious SSRF / local-file-read angles of letting users hand us a URL that a
 * server-side process then fetches.
 */
public class GitCloner {

    private static final int CLONE_TIMEOUT_SECONDS = 60;

    public static void clone(String repoUrl, Path targetDir) throws IOException, InterruptedException {
        validate(repoUrl);

        Process process = new ProcessBuilder(
                "git", "clone", "--depth", "1", "--single-branch", repoUrl, targetDir.toString())
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git clone timed out after " + CLONE_TIMEOUT_SECONDS + "s");
        }
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() != 0) {
            throw new IOException("git clone failed: " + output.trim());
        }
    }

    /** Clones into targetDir if it isn't a git repo yet; otherwise fast-forward pulls it. */
    public static void cloneOrUpdate(String repoUrl, Path targetDir) throws IOException, InterruptedException {
        if (java.nio.file.Files.isDirectory(targetDir.resolve(".git"))) {
            pull(targetDir);
        } else {
            java.nio.file.Files.createDirectories(targetDir.getParent());
            clone(repoUrl, targetDir);
        }
    }

    private static void pull(Path repoDir) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "-C", repoDir.toString(), "pull", "--ff-only")
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git pull timed out after " + CLONE_TIMEOUT_SECONDS + "s");
        }
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() != 0) {
            throw new IOException("git pull failed: " + output.trim());
        }
    }

    public static void validate(String repoUrl) throws IOException {
        URI uri;
        try {
            uri = new URI(repoUrl);
        } catch (Exception e) {
            throw new IOException("Not a valid URL: " + repoUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Only http:// and https:// git URLs are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL must have a host");
        }
        if (isLocalOrPrivate(host)) {
            throw new IOException("Refusing to clone from a local/private address");
        }
    }

    private static boolean isLocalOrPrivate(String host) {
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".localhost") || h.equals("0.0.0.0")) return true;
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isAnyLocalAddress();
        } catch (Exception e) {
            // If we can't resolve it, let git's own resolution fail later rather than block valid hosts.
            return false;
        }
    }
}
