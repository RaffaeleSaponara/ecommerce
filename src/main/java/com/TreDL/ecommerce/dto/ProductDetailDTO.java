package com.TreDL.ecommerce.dto;

import java.util.List;

public record ProductDetailDTO(ProductDTO product, List<ProductImageDTO> images) {}
