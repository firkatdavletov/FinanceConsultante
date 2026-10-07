package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryList
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryListService
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryMutationService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
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

data class CreateCategoryRequest(
    val name: String? = null,
    val kind: String? = null,
    val icon: String? = null,
)

data class UpdateCategoryRequest(
    val name: String? = null,
    val icon: String? = null,
)

@RestController
@RequestMapping("/api/v1/categories", produces = [MediaType.APPLICATION_JSON_VALUE])
@ConditionalOnProperty(prefix = "app.mobile-api", name = ["enabled"], havingValue = "true")
class CategoryController(
    private val categories: CategoryListService,
    private val mutations: CategoryMutationService,
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

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: CreateCategoryRequest,
    ): ResponseEntity<CategoryResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        val created = mutations.create(telegramUserId, request.name, request.kind, request.icon)
        return ResponseEntity
            .created(URI.create("/api/v1/categories/${created.id}"))
            .cacheControl(CacheControl.noStore())
            .body(CategoryResponse.from(created))
    }

    @PatchMapping("/{categoryId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable categoryId: UUID,
        @RequestBody request: UpdateCategoryRequest,
    ): ResponseEntity<CategoryResponse> {
        val telegramUserId = authenticator.authenticate(authorization)
        val updated = mutations.update(telegramUserId, categoryId, request.name, request.icon)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(CategoryResponse.from(updated))
    }

    @DeleteMapping("/{categoryId}")
    fun delete(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @PathVariable categoryId: UUID,
    ): ResponseEntity<Void> {
        val telegramUserId = authenticator.authenticate(authorization)
        mutations.delete(telegramUserId, categoryId)
        return ResponseEntity
            .noContent()
            .cacheControl(CacheControl.noStore())
            .build()
    }
}
