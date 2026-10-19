package com.yeodam.yeodambe.trip.service;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsBandFormat;
import app.photofox.vipsffm.enums.VipsInterpretation;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class HeifAttachmentDecoder {
    private static final MemoryLayout ERROR = MemoryLayout.structLayout(JAVA_INT, JAVA_INT, ADDRESS);
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup(
            NativeImageLibraries.heifPath(),
            Arena.global());
    private static final MethodHandle INIT = bind("heif_init", ERROR, ADDRESS);
    private static final MethodHandle ALLOC = bind("heif_context_alloc", ADDRESS);
    private static final MethodHandle FREE = bind("heif_context_free", null, ADDRESS);
    private static final MethodHandle LIMIT = bind("heif_context_set_maximum_image_size_limit", null, ADDRESS, JAVA_INT);
    private static final MethodHandle THREADS = bind("heif_context_set_max_decoding_threads", null, ADDRESS, JAVA_INT);
    private static final MethodHandle READ = bind("heif_context_read_from_file", ERROR, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle IDS = bind("heif_context_get_list_of_top_level_image_IDs",
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodHandle HANDLE = bind("heif_context_get_image_handle", ERROR, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle RELEASE_HANDLE = bind("heif_image_handle_release", null, ADDRESS);
    private static final MethodHandle OPTIONS = bind("heif_decoding_options_alloc", ADDRESS);
    private static final MethodHandle FREE_OPTIONS = bind("heif_decoding_options_free", null, ADDRESS);
    private static final MethodHandle HAS_ALPHA = bind("heif_image_handle_has_alpha_channel", JAVA_INT, ADDRESS);
    private static final MethodHandle DECODE = bind("heif_decode_image", ERROR,
            ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
    private static final MethodHandle RELEASE_IMAGE = bind("heif_image_release", null, ADDRESS);
    private static final MethodHandle WIDTH = bind("heif_image_get_width", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle HEIGHT = bind("heif_image_get_height", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle PLANE = bind("heif_image_get_plane_readonly", ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle PROFILE_SIZE = bind("heif_image_handle_get_raw_color_profile_size", JAVA_LONG, ADDRESS);
    private static final MethodHandle PROFILE = bind("heif_image_handle_get_raw_color_profile", ERROR, ADDRESS, ADDRESS);
    private static final int MAXIMUM_WIDTH = 16384;
    private static final int RGB_COLORSPACE = 1;
    private static final int RGB_CHROMA = 10;
    private static final int RGBA_CHROMA = 11;
    private static final int INTERLEAVED_CHANNEL = 10;

    static {
        try (Arena arena = Arena.ofConfined()) {
            check((MemorySegment) call(INIT, arena, MemorySegment.NULL));
        }
    }

    private HeifAttachmentDecoder() {
    }

    static Decoded decode(Arena arena, Path source) {
        MemorySegment context = requirePointer((MemorySegment) call(ALLOC));
        MemorySegment handle = MemorySegment.NULL;
        MemorySegment options = MemorySegment.NULL;
        MemorySegment image = MemorySegment.NULL;
        try {
            // libvips heifload와 같은 보안 상한을 디코딩 전에 적용한다.
            call(LIMIT, context, MAXIMUM_WIDTH);
            call(THREADS, context, 0);
            check((MemorySegment) call(READ, arena, context, arena.allocateFrom(source.toString()), MemorySegment.NULL));
            MemorySegment id = arena.allocate(JAVA_INT);
            if ((int) call(IDS, context, id, 1) != 1) {
                throw new IllegalArgumentException("HEIC의 첫 이미지를 찾을 수 없습니다.");
            }
            int itemId = id.get(JAVA_INT, 0);
            MemorySegment output = arena.allocate(ADDRESS);
            check((MemorySegment) call(HANDLE, arena, context, itemId, output));
            handle = requirePointer(output.get(ADDRESS, 0));
            options = requirePointer((MemorySegment) call(OPTIONS));
            // 회전·반전·유효 영역은 libheif가 한 번 적용하며 EXIF로 다시 회전하지 않는다.
            options.reinterpret(2).set(JAVA_BYTE, 1, (byte) 0);
            int bands = (int) call(HAS_ALPHA, handle) == 0 ? 3 : 4;
            check((MemorySegment) call(DECODE, arena, handle, output, RGB_COLORSPACE,
                    bands == 3 ? RGB_CHROMA : RGBA_CHROMA, options));
            image = requirePointer(output.get(ADDRESS, 0));
            VImage pixels = copyPixels(arena, image, bands);
            byte[] profile = profile(arena, handle);
            if (profile != null) {
                MemorySegment data = arena.allocateFrom(JAVA_BYTE, profile);
                VipsHelper.image_set_blob_copy(arena, pixels.getUnsafeStructAddress(),
                        "icc-profile-data", data, data.byteSize());
            }
            return new Decoded(pixels, profile);
        } finally {
            release(RELEASE_IMAGE, image);
            release(FREE_OPTIONS, options);
            release(RELEASE_HANDLE, handle);
            release(FREE, context);
        }
    }

    private static VImage copyPixels(Arena arena, MemorySegment image, int bands) {
        int width = (int) call(WIDTH, image, INTERLEAVED_CHANNEL);
        int height = (int) call(HEIGHT, image, INTERLEAVED_CHANNEL);
        if (width < 1 || height < 1 || (long) width * height > (long) MAXIMUM_WIDTH * MAXIMUM_WIDTH) {
            throw new IllegalArgumentException("HEIC의 픽셀 크기가 허용 범위를 벗어났습니다.");
        }
        MemorySegment strideOutput = arena.allocate(JAVA_INT);
        MemorySegment plane = requirePointer((MemorySegment) call(PLANE, image, INTERLEAVED_CHANNEL, strideOutput));
        int stride = strideOutput.get(JAVA_INT, 0);
        long rowBytes = (long) width * bands;
        if (stride < rowBytes) {
            throw new IllegalArgumentException("HEIC 픽셀 행의 크기가 올바르지 않습니다.");
        }
        MemorySegment source = plane.reinterpret((long) stride * height);
        MemorySegment pixels = arena.allocate(rowBytes * height);
        for (int row = 0; row < height; row++) {
            MemorySegment.copy(source, (long) row * stride, pixels, row * rowBytes, rowBytes);
        }
        return VImage.newFromMemory(arena, pixels, width, height, bands, VipsBandFormat.FORMAT_UCHAR.getRawValue())
                .copy(VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB));
    }

    private static byte[] profile(Arena arena, MemorySegment handle) {
        long size = (long) call(PROFILE_SIZE, handle);
        if (size == 0) {
            return null;
        }
        if (size < 0 || size > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("HEIC 색상 프로필이 허용 크기를 벗어났습니다.");
        }
        MemorySegment profile = arena.allocate(size);
        check((MemorySegment) call(PROFILE, arena, handle, profile));
        return profile.toArray(JAVA_BYTE);
    }

    private static MethodHandle bind(String name, MemoryLayout result, MemoryLayout... parameters) {
        FunctionDescriptor descriptor = result == null
                ? FunctionDescriptor.ofVoid(parameters) : FunctionDescriptor.of(result, parameters);
        return Linker.nativeLinker().downcallHandle(LIBRARY.find(name).orElseThrow(), descriptor);
    }

    private static Object call(MethodHandle function, Object... arguments) {
        try {
            return function.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("libheif 네이티브 호출에 실패했습니다.", failure);
        }
    }

    private static void check(MemorySegment error) {
        if (error.get(JAVA_INT, 0) != 0) {
            throw new IllegalArgumentException("HEIC 디코딩에 실패했습니다. code=" + error.get(JAVA_INT, 0)
                    + ", subcode=" + error.get(JAVA_INT, 4));
        }
    }

    private static MemorySegment requirePointer(MemorySegment pointer) {
        if (pointer.address() == 0) {
            throw new IllegalStateException("libheif 네이티브 메모리를 할당할 수 없습니다.");
        }
        return pointer;
    }

    private static void release(MethodHandle function, MemorySegment pointer) {
        if (pointer.address() != 0) {
            call(function, pointer);
        }
    }

    record Decoded(
            VImage pixels,
            byte[] profile
    ) {
    }
}
