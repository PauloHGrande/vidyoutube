package com.youtube;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class DownloadController {

    private static final Path DOWNLOAD_DIR = Paths.get(System.getProperty("user.dir"), "downloads");
    private static final String YT_DLP = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "yt-dlp.exe" : "yt-dlp";

    // Caminhos comuns do Node.js no Windows para garantir que yt-dlp encontre
    private static final String NODE_PATHS = String.join(";",
            "C:\\Program Files\\nodejs",
            "C:\\Program Files (x86)\\nodejs",
            System.getProperty("user.home") + "\\AppData\\Roaming\\npm"
    );

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private ProcessBuilder createProcess(List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // Garante que o PATH do sistema inclui o Node.js
        String currentPath = pb.environment().getOrDefault("PATH", "");
        if (!currentPath.contains("nodejs")) {
            pb.environment().put("PATH", NODE_PATHS + ";" + currentPath);
        }
        return pb;
    }

    // ── Vídeo único ──────────────────────────────────────────────────────────

    @GetMapping(value = "/download", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter download(@RequestParam String url,
                               @RequestParam(defaultValue = "DEFAULT") String format,
                               @RequestParam(defaultValue = "") String browser) {
        SseEmitter emitter = new SseEmitter(0L);

        executor.submit(() -> {
            try {
                Files.createDirectories(DOWNLOAD_DIR);

                List<String> command = buildVideoCommand(url, format, browser, DOWNLOAD_DIR);
                ProcessBuilder pb = createProcess(command);

                Process process = pb.start();
                String[] lastFile = {null};

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String t = line.trim();
                        if (t.contains("[download]") || t.contains("[ffmpeg]")
                                || t.contains("[ExtractAudio]") || t.contains("Destination")
                                || t.contains("ERROR") || t.contains("WARNING")) {
                            emitter.send(SseEmitter.event().name("progress").data(t));
                            if (t.contains("Destination:")) {
                                lastFile[0] = t.substring(t.indexOf("Destination:") + 12).trim();
                            }
                        }
                    }
                }

                int exit = process.waitFor();
                if (exit == 0) {
                    File latest = findLatestFile(DOWNLOAD_DIR.toFile());
                    if (latest != null) lastFile[0] = latest.getName();
                    String fileName = lastFile[0] != null ? Paths.get(lastFile[0]).getFileName().toString() : "";
                    emitter.send(SseEmitter.event().name("done").data(fileName));
                } else {
                    emitter.send(SseEmitter.event().name("error").data("yt-dlp falhou com código " + exit));
                }
                emitter.complete();

            } catch (Exception e) {
                sendError(emitter, e.getMessage());
            }
        });

        return emitter;
    }

    // ── Playlist ─────────────────────────────────────────────────────────────

    @GetMapping(value = "/playlist", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter playlist(@RequestParam String url,
                               @RequestParam(defaultValue = "DEFAULT") String format,
                               @RequestParam(defaultValue = "") String browser) {
        SseEmitter emitter = new SseEmitter(0L);

        executor.submit(() -> {
            try {
                Files.createDirectories(DOWNLOAD_DIR);

                String playlistName = getPlaylistTitle(url, browser);
                Path playlistDir = DOWNLOAD_DIR.resolve(sanitize(playlistName));
                Files.createDirectories(playlistDir);

                emitter.send(SseEmitter.event().name("info")
                        .data("Playlist: " + playlistName));

                List<String> command = buildPlaylistCommand(url, format, browser, playlistDir);
                ProcessBuilder pb = createProcess(command);

                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String t = line.trim();
                        if (t.contains("[download]") || t.contains("[ffmpeg]")
                                || t.contains("[ExtractAudio]") || t.contains("Destination")
                                || t.contains("Downloading item") || t.contains("ERROR")
                                || t.contains("WARNING")) {
                            emitter.send(SseEmitter.event().name("progress").data(t));
                        }
                    }
                }

                int exit = process.waitFor();

                // Verifica se há arquivos baixados mesmo com erros parciais
                File[] downloaded = playlistDir.toFile().listFiles(
                        f -> f.isFile() && !f.getName().endsWith(".part"));

                if (exit != 0 && (downloaded == null || downloaded.length == 0)) {
                    emitter.send(SseEmitter.event().name("error")
                            .data("Nenhum vídeo foi baixado. Verifique se está logado no YouTube no navegador selecionado."));
                    emitter.complete();
                    return;
                }

                emitter.send(SseEmitter.event().name("progress").data("Compactando " +
                        (downloaded != null ? downloaded.length : 0) + " arquivos em ZIP..."));

                String zipName = sanitize(playlistName) + ".zip";
                Path zipPath = DOWNLOAD_DIR.resolve(zipName);
                zipFolder(playlistDir, zipPath);

                emitter.send(SseEmitter.event().name("done").data(zipName));
                emitter.complete();

            } catch (Exception e) {
                sendError(emitter, e.getMessage());
            }
        });

        return emitter;
    }

    // ── Servir arquivo ───────────────────────────────────────────────────────

    @GetMapping("/file/{filename}")
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {
        Path filePath = DOWNLOAD_DIR.resolve(filename).normalize();
        if (!filePath.startsWith(DOWNLOAD_DIR)) return ResponseEntity.badRequest().build();

        File file = filePath.toFile();
        if (!file.exists()) return ResponseEntity.notFound().build();

        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<String> buildVideoCommand(String url, String format, String browser, Path outDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        cmd.add("-o");
        cmd.add(outDir.resolve("%(title)s.%(ext)s").toString());
        cmd.add("--progress");
        cmd.add("--newline");
        cmd.add("--no-playlist");
        cmd.add("--js-runtimes");
        cmd.add("node");
        addBrowser(cmd, browser);
        applyFormat(cmd, format);

        cmd.add(url);
        return cmd;
    }

    private List<String> buildPlaylistCommand(String url, String format, String browser, Path outDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        cmd.add("-o");
        cmd.add(outDir.resolve("%(playlist_index)s - %(title)s.%(ext)s").toString());
        cmd.add("--progress");
        cmd.add("--newline");
        cmd.add("--yes-playlist");
        cmd.add("--ignore-errors");
        cmd.add("--js-runtimes");
        cmd.add("node");
        addBrowser(cmd, browser);

        applyFormat(cmd, format);
        cmd.add(url);
        return cmd;
    }

    private void addBrowser(List<String> cmd, String browser) {
        // Tenta usar arquivo cookies.txt primeiro (mais confiável)
        Path cookiesFile = Paths.get(System.getProperty("user.dir"), "cookies.txt");
        if (Files.exists(cookiesFile)) {
            cmd.add("--cookies");
            cmd.add(cookiesFile.toString());
            return;
        }
        if (browser != null && !browser.isBlank() && !browser.equals("none")) {
            cmd.add("--cookies-from-browser");
            cmd.add(browser);
        }
    }

    private void applyFormat(List<String> cmd, String format) {
        switch (format) {
            case "DEFAULT" -> {
                // Prefere mp4 com vídeo+áudio já embutidos, fallback para merge com ffmpeg
                cmd.add("-f");
                cmd.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
                cmd.add("--merge-output-format");
                cmd.add("mp4");
            }
            case "BEST_VIDEO" -> {
                cmd.add("-f");
                cmd.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
                cmd.add("--merge-output-format");
                cmd.add("mp4");
            }
            case "AUDIO_ONLY" -> {
                cmd.add("-x");
                cmd.add("--audio-format");
                cmd.add("mp3");
                cmd.add("--audio-quality");
                cmd.add("0");
            }
            case "WORST_VIDEO" -> {
                cmd.add("-f");
                cmd.add("worstvideo+worstaudio/worst");
            }
        }
    }

    private String getPlaylistTitle(String url, String browser) {
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    YT_DLP, "--flat-playlist", "--print", "%(playlist_title)s",
                    "--playlist-items", "1",
                    "--js-runtimes", "node"));
            addBrowser(cmd, browser);
            cmd.add(url);

            ProcessBuilder pb = createProcess(cmd);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("WARNING") || line.startsWith("ERROR") || line.isBlank()) continue;
                    if (!line.equals("NA")) {
                        p.waitFor();
                        return line.trim();
                    }
                }
                p.waitFor();
            }
        } catch (Exception ignored) {}
        return "playlist_" + System.currentTimeMillis();
    }

    private void zipFolder(Path source, Path zipTarget) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipTarget.toFile()))) {
            Files.walk(source)
                    .filter(p -> !Files.isDirectory(p))
                    .forEach(p -> {
                        ZipEntry entry = new ZipEntry(source.relativize(p).toString());
                        try {
                            zos.putNextEntry(entry);
                            Files.copy(p, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private File findLatestFile(File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().endsWith(".part"));
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) latest = f;
        }
        return latest;
    }

    private void sendError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("error").data(msg));
            emitter.complete();
        } catch (IOException ignored) {}
    }
}
