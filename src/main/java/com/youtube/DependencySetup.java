package com.youtube;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class DependencySetup implements ApplicationRunner {

    static final Path TOOLS_DIR = Paths.get(System.getProperty("user.dir"), "tools");
    static final Path FFMPEG_BIN = TOOLS_DIR.resolve("ffmpeg").resolve("bin");

    private static final String FFMPEG_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("\n=== Verificando dependências ===");
        try { Files.createDirectories(TOOLS_DIR); } catch (IOException ignored) {}

        checkYtDlp();
        checkNodeJs();
        checkFfmpeg();

        System.out.println("================================\n");
    }

    private void checkYtDlp() {
        if (isAvailable("yt-dlp")) {
            System.out.println("[OK] yt-dlp encontrado");
        } else {
            System.err.println("[ERRO] yt-dlp nao encontrado! Execute: pip install yt-dlp");
        }
    }

    private void checkNodeJs() {
        if (isAvailable("node")) {
            System.out.println("[OK] Node.js encontrado");
        } else {
            System.err.println("[ERRO] Node.js nao encontrado! Baixe em: https://nodejs.org");
        }
    }

    private void checkFfmpeg() {
        if (isAvailable("ffmpeg") || Files.exists(FFMPEG_BIN.resolve("ffmpeg.exe"))) {
            System.out.println("[OK] ffmpeg encontrado");
            return;
        }

        System.out.println("[INFO] ffmpeg nao encontrado. Baixando automaticamente (~80MB)...");
        try {
            downloadFfmpeg();
            System.out.println("[OK] ffmpeg instalado em tools/ffmpeg/bin/");
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao baixar ffmpeg: " + e.getMessage());
            System.err.println("[INFO] Instale manualmente: winget install ffmpeg");
        }
    }

    private void downloadFfmpeg() throws Exception {
        Path zipFile = TOOLS_DIR.resolve("ffmpeg.zip");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FFMPEG_URL))
                .header("User-Agent", "Mozilla/5.0")
                .build();

        client.send(request, HttpResponse.BodyHandlers.ofFile(zipFile));
        System.out.println("[INFO] Download concluido. Extraindo...");

        Files.createDirectories(FFMPEG_BIN);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String fileName = Paths.get(entry.getName()).getFileName().toString();
                if (fileName.equals("ffmpeg.exe") || fileName.equals("ffprobe.exe")) {
                    Files.copy(zis, FFMPEG_BIN.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[INFO] Extraido: " + fileName);
                }
                zis.closeEntry();
            }
        }

        Files.deleteIfExists(zipFile);
    }

    static boolean isAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
