package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.EmailNotVerified
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.mobileapi.application.CATEGORY_USAGE_PAGE_SIZE
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsage
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsageConflict
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsageCursorExpired
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsageEvent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

@RestController
@RequestMapping("/api/v4/category-usage/events")
@ConditionalOnProperty(name = ["app.firebase-auth.enabled", "app.mobile-api.enabled"], havingValue = "true")
class CategoryUsageController(
    private val authentication: FirebaseAuthenticationService,
    private val usage: CategoryUsage,
) {
    @PostMapping
    fun push(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam budgetId: String,
        @RequestBody body: JsonNode,
    ) = noStore(usage.push(authentication.authenticate(authorization).firebaseUid, parseUuid(budgetId), events(body)))

    @GetMapping
    fun pull(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam budgetId: String,
        @RequestParam(required = false) cursor: String?,
    ) = noStore(usage.pull(authentication.authenticate(authorization).firebaseUid, parseUuid(budgetId), cursor))

    @ExceptionHandler(HttpMessageNotReadableException::class, MissingServletRequestParameterException::class)
    fun invalidRequest() = error(HttpStatus.BAD_REQUEST, "invalid_request")

    @ExceptionHandler(EmailNotVerified::class)
    fun emailNotVerified() = error(HttpStatus.FORBIDDEN, "access_denied")

    @ExceptionHandler(CategoryUsageConflict::class)
    fun conflict() = error(HttpStatus.CONFLICT, "event_conflict")

    @ExceptionHandler(CategoryUsageCursorExpired::class)
    fun expired() = error(HttpStatus.GONE, "cursor_expired")

    private fun events(body: JsonNode): List<CategoryUsageEvent> {
        val events = requireNotNull(body.get("events"))
        require(events.isArray && events.size() in 1..CATEGORY_USAGE_PAGE_SIZE)
        return events.values().map { event ->
            val millis = requireNotNull(event.get("usedAtMillis"))
            require(millis.isIntegralNumber && millis.canConvertToLong() && millis.longValue() >= 0)
            CategoryUsageEvent(
                event.uuid("id"),
                event.uuid("operationId"),
                event.uuid("categoryId"),
                millis.longValue(),
            )
        }
    }

    private fun JsonNode.uuid(field: String): UUID {
        val value = requireNotNull(get(field))
        require(value.isString)
        return parseUuid(value.asString())
    }

    private fun parseUuid(value: String): UUID = UUID.fromString(value).also { require(it.toString() == value) }

    private fun <T> noStore(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)

    private fun error(
        status: HttpStatus,
        code: String,
    ) = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("code" to code))
}
