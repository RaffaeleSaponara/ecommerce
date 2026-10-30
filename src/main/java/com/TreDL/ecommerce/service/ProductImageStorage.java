package com.TreDL.ecommerce.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

@Component
public class ProductImageStorage {
    public static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final String KEY_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png";
    private final Path directory;

    public ProductImageStorage(@Value("${product.images.directory:${user.home}/.3dl/product-images}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) {
        BufferedImage decoded = decode(file);
        String key = UUID.randomUUID() + ".png";
        try {
            checkDirectory();
            Files.createDirectories(directory);
            checkDirectory();
            Path target = directory.resolve(key);
            try (OutputStream output = Channels.newOutputStream(Files.newByteChannel(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                if (!ImageIO.write(decoded, "png", output)) throw new IOException("PNG encoder unavailable");
            } catch (IOException | RuntimeException failure) {
                Files.deleteIfExists(target);
                throw failure;
            }
            return key;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Impossibile salvare l'immagine", error);
        }
    }

    private BufferedImage decode(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid();
        if (file.getSize() > MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Immagine troppo grande (massimo 5 MiB)");
        String mime = file.getContentType();
        if (!"image/png".equalsIgnoreCase(mime) && !"image/jpeg".equalsIgnoreCase(mime)) throw invalid();
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Immagine troppo grande (massimo 5 MiB)");
            boolean png = bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8),
                    new byte[]{(byte)137,80,78,71,13,10,26,10});
            boolean jpeg = bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255;
            if (!(png && "image/png".equalsIgnoreCase(mime)) && !(jpeg && "image/jpeg".equalsIgnoreCase(mime))) throw invalid();
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw invalid();
                ImageReader reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!(png && format.equals("png")) && !(jpeg && (format.equals("jpeg") || format.equals("jpg")))) throw invalid();
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 16_000_000)
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dimensioni immagine non valide (4096 px, 16 MP)");
                    BufferedImage image = reader.read(0);
                    if (image == null) throw invalid();
                    // Normalize color model too, so embedded profiles cannot survive re-encoding.
                    BufferedImage normalized = new BufferedImage(width, height,
                            image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D graphics = normalized.createGraphics();
                    try { graphics.drawImage(image, 0, 0, null); }
                    finally { graphics.dispose(); }
                    return normalized;
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    public byte[] read(String key) {
        try {
            Path target = checkedPath(key);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw missing();
            try (InputStream input = Channels.newInputStream(Files.newByteChannel(target,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                return input.readAllBytes();
            }
        } catch (IOException error) { throw missing(); }
    }

    public void delete(String key) {
        try { Files.deleteIfExists(checkedPath(key)); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private Path checkedPath(String key) throws IOException {
        if (key == null || !key.matches(KEY_PATTERN)) throw missing();
        checkDirectory();
        Path path = directory.resolve(key);
        if (Files.isSymbolicLink(path)) throw missing();
        return path;
    }

    private void checkDirectory() throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed in image storage");
            // Resolve existing ancestors too: Windows junctions are not symbolic links.
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().equals(current))
                throw new IOException("Redirected paths are not allowed in image storage");
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caricare un'immagine PNG o JPEG valida");
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Immagine non trovata");
    }
}
