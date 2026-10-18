package com.yeodam.yeodambe.trip.service;

import app.photofox.vipsffm.VImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeImageLibrariesTest {
    @TempDir
    Path directory;

    @Test
    void 명시한_HEIF_경로는_자동_탐색으로_덮어쓰지_않는다() {
        String property = "app.photo.libheif.path";
        String previous = System.getProperty(property);
        String explicit = directory.resolve("custom-heif").toString();
        try {
            System.setProperty(property, explicit);
            assertEquals(explicit, NativeImageLibraries.heifPath());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void JVM_옵션과_추가_PATH_없는_새_프로세스에서도_JPEG와_HEIC를_변환한다() throws Exception {
        String classpath = List.of(NativeImageLibrariesTest.class, VipsAttachmentConverter.class, VImage.class)
                .stream()
                .map(this::classLocation)
                .collect(Collectors.joining(File.pathSeparator));
        Path javaDirectory = Path.of(System.getProperty("java.home"), "bin");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path log = directory.resolve("native-process.log");
        ProcessBuilder builder = new ProcessBuilder(javaDirectory.resolve(executable).toString(),
                "-cp", classpath, Probe.class.getName(), directory.toString(),
                Path.of("src/test/resources/images/heic/rotation-90.heic").toAbsolutePath().toString());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "DYLD_LIBRARY_PATH", "DYLD_FALLBACK_LIBRARY_PATH", "LD_LIBRARY_PATH")) {
            builder.environment().remove(variable);
        }
        // GUI로 실행한 IntelliJ처럼 패키지 관리자의 bin 경로가 없는 환경을 재현한다.
        if (!System.getProperty("os.name").startsWith("Windows")) {
            builder.environment().put("PATH", javaDirectory.toString());
        }
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "새 JVM의 네이티브 변환이 시간 내 끝나야 합니다.");
            assertEquals(0, process.exitValue(), () -> read(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private String classLocation(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static class Probe {
        public static void main(String[] arguments) throws Exception {
            Path directory = Path.of(arguments[0]);
            Path source = Path.of(arguments[1]);
            Path analyze = directory.resolve("analyze.jpg");
            Path preview = directory.resolve("preview.webp");
            Path display = directory.resolve("display.jpg");
            VipsAttachmentConverter.convert(source, "image/heic", analyze, preview, display, 92);
            for (Path output : List.of(analyze, preview, display)) {
                if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                    throw new AssertionError("필수 파생본이 없습니다: " + output);
                }
            }
            VipsAttachmentConverter.convert(display, "image/jpeg", analyze, preview, display, 92);
        }
    }
}
