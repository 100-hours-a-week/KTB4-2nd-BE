package com.yeodam.yeodambe.trip.service;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class NativeImageLibraries {
    private static boolean initialized;

    private NativeImageLibraries() {
    }

    static synchronized void initialize() {
        if (initialized) {
            return;
        }
        for (String library : List.of("glib", "gobject", "vips")) {
            String property = "vipsffm.libpath." + library + ".override";
            if (System.getProperty(property) == null) {
                Library found = locate(library);
                // vips-ffm의 Path 기반 override에는 절대 경로만 전달한다.
                if (found.absolutePath()) {
                    System.setProperty(property, found.location());
                }
            }
        }
        initialized = true;
    }

    static String heifPath() {
        String override = System.getProperty("app.photo.libheif.path");
        return override == null ? locate("heif").location() : override;
    }

    private static Library locate(String library) {
        List<String> names = names(library);
        IllegalArgumentException lastFailure = null;
        for (String name : names) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(name, arena);
                return new Library(name, false);
            } catch (IllegalArgumentException failure) {
                lastFailure = failure;
            }
        }
        List<Path> directories = directories();
        for (Path directory : directories) {
            for (String name : names) {
                Path candidate = directory.resolve(name);
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                try (Arena arena = Arena.ofConfined()) {
                    SymbolLookup.libraryLookup(candidate, arena);
                    return new Library(candidate.toString(), true);
                } catch (IllegalArgumentException failure) {
                    lastFailure = failure;
                }
            }
        }
        throw new IllegalStateException("이미지 변환 라이브러리를 찾거나 로드할 수 없습니다: " + library
                + ". 현재 JVM의 OS·아키텍처에 맞는 libvips·libheif 설치와 종속 라이브러리를 확인하세요."
                + " 파일명=" + names + ", 탐색 디렉터리=" + directories, lastFailure);
    }

    private static List<String> names(String library) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String stem = switch (library) {
            case "glib" -> "glib-2.0";
            case "gobject" -> "gobject-2.0";
            default -> library;
        };
        String abi = switch (library) {
            case "vips" -> System.getProperty("vipsffm.abinumber.vips.override", "42");
            case "heif" -> "1";
            default -> System.getProperty("vipsffm.abinumber." + library + ".override", "0");
        };
        if (os.startsWith("mac")) {
            return List.of("lib" + stem + ".dylib", "lib" + stem + "." + abi + ".dylib");
        }
        if (os.startsWith("windows")) {
            if ("heif".equals(library)) {
                return List.of("libheif.dll", "heif.dll", "libheif-1.dll");
            }
            return List.of(stem + ".dll", stem + "." + abi + ".dll", "lib" + stem + "-" + abi + ".dll");
        }
        return List.of("lib" + stem + ".so." + abi, "lib" + stem + ".so");
    }

    private static List<Path> directories() {
        Set<Path> directories = new LinkedHashSet<>();
        addPaths(directories, System.getProperty("java.library.path", ""));
        addPaths(directories, System.getenv("PATH"));
        List<Path> executableDirectories = new ArrayList<>(directories);
        for (Path directory : executableDirectories) {
            Path parent = directory.getParent();
            if (parent != null && directory.endsWith("bin")) {
                directories.add(parent.resolve("lib"));
            }
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            directories.addAll(List.of(Path.of("/opt/homebrew/lib"), Path.of("/usr/local/lib"), Path.of("/opt/local/lib")));
        } else if (os.startsWith("windows")) {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) {
                directories.add(Path.of(programFiles, "vips", "bin"));
                directories.add(Path.of(programFiles, "libvips", "bin"));
            }
            directories.add(Path.of(System.getProperty("user.home"), "scoop", "apps", "vips", "current", "bin"));
        } else {
            directories.addAll(List.of(Path.of("/usr/local/lib"), Path.of("/usr/local/lib64"), Path.of("/usr/lib"),
                    Path.of("/usr/lib64"), Path.of("/lib"), Path.of("/lib64")));
            String architecture = System.getProperty("os.arch");
            String multiarch = switch (architecture) {
                case "amd64", "x86_64" -> "x86_64-linux-gnu";
                case "aarch64", "arm64" -> "aarch64-linux-gnu";
                default -> architecture + "-linux-gnu";
            };
            directories.add(Path.of("/usr/lib", multiarch));
            directories.add(Path.of("/lib", multiarch));
        }
        return directories.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    }

    private static void addPaths(Set<Path> directories, String paths) {
        if (paths != null) {
            Arrays.stream(paths.split(Pattern.quote(File.pathSeparator)))
                    .filter(path -> !path.isBlank())
                    .map(Path::of)
                    .forEach(directories::add);
        }
    }

    private record Library(
            String location,
            boolean absolutePath
    ) {
    }
}
