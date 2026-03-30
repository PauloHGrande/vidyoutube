package com.youtube;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class YtDlpWrapper {

    private final String ytDlpPath;
    private final Path outputDir;

    public YtDlpWrapper(String ytDlpPath, Path outputDir) {
        this.ytDlpPath = ytDlpPath;
        this.outputDir = outputDir;
    }

    public void download(String url, VideoFormat format, ProgressListener listener) throws IOException, InterruptedException {
        List<String> command = buildCommand(url, format);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        listener.onStart(url);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                listener.onOutput(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode == 0) {
            listener.onSuccess();
        } else {
            listener.onError("yt-dlp terminou com código de saída: " + exitCode);
        }
    }

    public List<FormatInfo> listFormats(String url) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--list-formats");
        command.add(url);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        List<FormatInfo> formats = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean tableStarted = false;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("ID ") || line.startsWith("---")) {
                    tableStarted = true;
                    continue;
                }
                if (tableStarted && !line.isBlank()) {
                    formats.add(FormatInfo.parse(line));
                }
            }
        }

        process.waitFor();
        return formats;
    }

    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ytDlpPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String version = reader.readLine();
                if (version != null && !version.isBlank()) {
                    System.out.println("yt-dlp versão: " + version.trim());
                    return true;
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            // not available
        }
        return false;
    }

    private List<String> buildCommand(String url, VideoFormat format) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);

        // Diretório de saída com template de nome
        command.add("-o");
        command.add(outputDir.resolve("%(title)s.%(ext)s").toString());

        // Formato de download
        switch (format) {
            case BEST_VIDEO -> {
                command.add("-f");
                command.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
            }
            case AUDIO_ONLY -> {
                command.add("-x");
                command.add("--audio-format");
                command.add("mp3");
                command.add("--audio-quality");
                command.add("0");
            }
            case WORST_VIDEO -> {
                command.add("-f");
                command.add("worstvideo+worstaudio/worst");
            }
            case DEFAULT -> {
                // yt-dlp escolhe o melhor automaticamente
            }
        }

        // Exibir barra de progresso simples
        command.add("--progress");
        command.add("--newline");

        command.add(url);
        return command;
    }

    public interface ProgressListener {
        void onStart(String url);
        void onOutput(String line);
        void onSuccess();
        void onError(String message);
    }

    public enum VideoFormat {
        DEFAULT("Melhor qualidade (padrão)"),
        BEST_VIDEO("Melhor vídeo MP4 + áudio"),
        AUDIO_ONLY("Somente áudio (MP3)"),
        WORST_VIDEO("Menor tamanho");

        private final String label;

        VideoFormat(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public record FormatInfo(String raw) {
        public static FormatInfo parse(String line) {
            return new FormatInfo(line);
        }

        @Override
        public String toString() {
            return raw;
        }
    }
}
