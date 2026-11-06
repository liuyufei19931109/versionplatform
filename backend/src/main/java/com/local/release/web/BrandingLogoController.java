package com.local.release.web;

import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BrandingLogoController {
  private static final int MAX_BYTES = 256 * 1024;
  private static final int MAX_EDGE = 1024;
  private final Path logo;

  public BrandingLogoController(@Value("${RELEASE_DATA_DIR:./data}") String dataDir) {
    logo = Path.of(dataDir).resolve("branding").resolve("logo.png");
  }

  @GetMapping("/api/branding/logo")
  public ResponseEntity<byte[]> get() throws IOException {
    try {
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
          .body(Files.readAllBytes(logo));
    } catch (NoSuchFileException absent) {
      return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
  }

  @PutMapping("/api/settings/branding/logo")
  public ResponseEntity<?> upload(HttpServletRequest request) throws IOException {
    if (!MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(request.getContentType())) {
      return ResponseEntity.status(415).body(Map.of("message", "请选择 PNG 图片"));
    }
    byte[] input;
    try (InputStream stream = request.getInputStream()) {
      input = stream.readNBytes(MAX_BYTES + 1);
    }
    if (input.length > MAX_BYTES) {
      return ResponseEntity.status(413).body(Map.of("message", "Logo 文件上限为 256 KiB"));
    }

    byte[] normalized = normalize(input);
    Files.createDirectories(logo.getParent());
    Path temporary = Files.createTempFile(logo.getParent(), "upload-", ".tmp");
    try {
      Files.write(temporary, normalized);
      Files.move(temporary, logo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/settings/branding/logo")
  public ResponseEntity<Void> clear() throws IOException {
    Files.deleteIfExists(logo);
    return ResponseEntity.noContent().build();
  }

  private static byte[] normalize(byte[] input) throws IOException {
    ImageReader reader = ImageIO.getImageReadersByFormatName("PNG").next();
    BufferedImage pixels;
    try (var image = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
      reader.setInput(image, true, true);
      if (reader.getWidth(0) > MAX_EDGE || reader.getHeight(0) > MAX_EDGE) {
        throw new IllegalArgumentException("Logo 的宽和高均不得超过 1024 像素");
      }
      pixels = reader.read(0);
    } catch (IOException | IndexOutOfBoundsException invalid) {
      throw new IllegalArgumentException("无法读取 PNG 图片，请重新导出后上传");
    } finally {
      reader.dispose();
    }
    // Persist only decoded pixels; metadata and trailing bytes need not become public.
    var output = new ByteArrayOutputStream();
    ImageIO.write(pixels, "png", output);
    return output.toByteArray();
  }
}
