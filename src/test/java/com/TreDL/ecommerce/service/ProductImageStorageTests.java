package com.TreDL.ecommerce.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ProductImageStorageTests {
    @TempDir Path temp;

    static byte[] image(String format, int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes);
        return bytes.toByteArray();
    }

    @Test void createsOnlyOnUploadAndReencodesWithoutTrailingPayload() throws Exception {
        Path directory = temp.resolve("images");
        ProductImageStorage storage = new ProductImageStorage(directory.toString());
        assertThat(directory).doesNotExist();
        byte[] original = image("png", 3, 2);
        byte[] withPayload = java.util.Arrays.copyOf(original, original.length + 100);
        java.util.Arrays.fill(withPayload, original.length, withPayload.length, (byte)65);
        String key = storage.store(new MockMultipartFile("file", "../../attack.png", "image/png", withPayload));
        assertThat(key).matches("[0-9a-f-]{36}\\.png");
        assertThat(storage.read(key)).isEqualTo(original);
        storage.delete(key);
        assertThat(directory.resolve(key)).doesNotExist();
    }

    @Test void jpegBecomesPng() throws Exception {
        ProductImageStorage storage = new ProductImageStorage(temp.toString());
        String key = storage.store(new MockMultipartFile("file", "x.jpg", "image/jpeg", image("jpeg", 4, 5)));
        assertThat(storage.read(key)).startsWith((byte)137, (byte)80, (byte)78, (byte)71);
        assertThat(ImageIO.read(temp.resolve(key).toFile()).getWidth()).isEqualTo(4);
    }

    @Test void rejectsMimeMismatchFakeImagesAndEmptyFiles() throws Exception {
        ProductImageStorage storage = new ProductImageStorage(temp.toString());
        for (MockMultipartFile file : new MockMultipartFile[]{
                new MockMultipartFile("file", "x.png", "image/jpeg", image("png", 1, 1)),
                new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg/>".getBytes()),
                new MockMultipartFile("file", "x.png", "image/png", "hello".getBytes()),
                new MockMultipartFile("file", "x.png", "image/png", new byte[0])}) {
            assertThatThrownBy(() -> storage.store(file)).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(400));
        }
        assertThat(temp.toFile().list()).isEmpty();
    }

    @Test void rejectsLargeBytesAndDimensionsBeforeStorage() throws Exception {
        ProductImageStorage storage = new ProductImageStorage(temp.toString());
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", "x.png", "image/png",
                new byte[(int)ProductImageStorage.MAX_BYTES + 1]))).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(413));
        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", "x.png", "image/png",
                image("png", 4097, 1)))).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(400));
        assertThat(temp.toFile().list()).isEmpty();
    }

    @Test void rejectsTraversalAndNonUuidKeys() {
        ProductImageStorage storage = new ProductImageStorage(temp.toString());
        for (String key : new String[]{"../secret", "/etc/passwd", "x.png", "00000000-0000-0000-0000-000000000000.png/.."}) {
            assertThatThrownBy(() -> storage.read(key)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(ResponseStatusException.class);
        }
    }
}
