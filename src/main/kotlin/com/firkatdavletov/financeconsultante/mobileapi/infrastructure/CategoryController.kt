package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryList
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryListService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CategoryListResponse(
    val items: List<CategoryResponse>,
) {
    companion object {
        fun from(categories: CategoryList): CategoryListResponse =
            CategoryListResponse(categories.items.map(CategoryResponse::from))
    }
}

data class CategoryResponse(
    val id: UUID,
    val name: String,
    val kind: String,
    val icon: CategoryIconResponse,
) {
    companion object {
        fun from(category: Category): CategoryResponse =
            CategoryResponse(
                category.id,
                category.name,
                category.kind.name,
                CategoryIconResponse(category.icon.name, category.icon.emoji),
            )
    }
}

data class CategoryIconResponse(
    val code: String,
    val emoji: String,
)

@RestController
@RequestMapping("/api/v1/categories", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class CategoryController(
    private val categories: CategoryListService,
    properties: MobileApiProperties,
) {
    private val authenticator = MobileApiAuthenticator(properties)

    @GetMapping
    fun categories(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<CategoryListResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(CategoryListResponse.from(categories.categories(telegramUserId)))
    }
}
