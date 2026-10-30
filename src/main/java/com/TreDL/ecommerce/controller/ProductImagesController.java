package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.service.ProductImagesService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

@RestController
public class ProductImagesController {
    private final ProductImagesService images;
    public ProductImagesController(ProductImagesService images) { this.images = images; }

    @GetMapping("/api/products/{id}")
    public ProductDetailDTO detail(@PathVariable long id) { return images.detail(id); }

    @GetMapping("/api/admin/products/{id}/images")
    public List<ProductImageDTO> list(@PathVariable long id) { return images.list(id); }

    @PostMapping(value = "/api/admin/products/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProductImageDTO upload(@PathVariable long id, @RequestParam("file") MultipartFile file,
                                  org.springframework.web.multipart.MultipartHttpServletRequest request) {
        if (request.getMultiFileMap().values().stream().mapToInt(List::size).sum() != 1)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Caricare un solo file");
        return images.upload(id, file);
    }

    @PutMapping("/api/admin/products/{id}/images/{imageId}/primary")
    public ProductImageDTO primary(@PathVariable long id, @PathVariable long imageId) { return images.primary(id, imageId); }

    @DeleteMapping("/api/admin/products/{id}/images/{imageId}")
    public Map<String, String> delete(@PathVariable long id, @PathVariable long imageId) {
        images.delete(id, imageId);
        return Map.of("message", "Immagine rimossa");
    }

    @GetMapping("/media/products/{key}")
    public ResponseEntity<byte[]> content(@PathVariable String key) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options", "nosniff")
                .body(images.content(key));
    }
}
