package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

internal class InvalidDeviceId : RuntimeException()

private val canonicalDeviceId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private const val MAX_VERSION_LENGTH = 64

private fun deviceId(request: HttpServletRequest): UUID {
    val values = request.getHeaders("X-Device-Id").toList()
    if (values.size != 1 || !canonicalDeviceId.matches(values.single())) throw InvalidDeviceId()
    return UUID.fromString(values.single())
}

private fun version(request: HttpServletRequest): String =
    request
        .getHeader("X-App-Version")
        .orEmpty()
        .filter { !it.isISOControl() }
        .take(MAX_VERSION_LENGTH)

@org.springframework.stereotype.Service
class InternalDeviceAccess(
    private val jdbc: JdbcTemplate,
) {
    fun isActive(id: UUID): Boolean =
        jdbc
            .query(
                "SELECT active FROM internal_app_installation WHERE device_id = ?",
                { result, _ -> result.getBoolean("active") },
                id,
            ).firstOrNull() ?: false
}

@RestController
class InternalDeviceAccessController(
    private val access: InternalDeviceAccess,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/v4/app/access", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun access(request: HttpServletRequest): ResponseEntity<Map<String, String>> =
        try {
            val mode = if (access.isActive(deviceId(request))) "INTERNAL_FULL" else "OFFLINE_FREE"
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf("mode" to mode))
        } catch (_: InvalidDeviceId) {
            logger.info("invalid_device_id appVersion={}", version(request))
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(mapOf("code" to "invalid_device_id"))
        } catch (_: DataAccessException) {
            logger.warn("device_access_unavailable appVersion={}", version(request))
            ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(mapOf("code" to "device_access_unavailable"))
        }
}

internal class InternalDeviceGateFilter(
    private val access: InternalDeviceAccess,
) : OncePerRequestFilter() {
    private val accessLogger = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val rawPath = request.requestURI.removePrefix(request.contextPath)
        val mappedPath = request.servletPath
        val cloudPath = rawPath.startsWith("/api/v4/") || mappedPath.startsWith("/api/v4/")
        val exemptPath =
            when (request.method) {
                "GET" -> rawPath == "/api/v4/app/access" || rawPath == "/api/v4/profile/deletion"
                "DELETE" -> rawPath == "/api/v4/profile"
                else -> false
            }
        val exempt = (mappedPath.isEmpty() || rawPath == mappedPath) && exemptPath
        return !cloudPath || exempt
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val allowed =
            try {
                access.isActive(deviceId(request))
            } catch (_: InvalidDeviceId) {
                false
            } catch (_: DataAccessException) {
                accessLogger.warn("device_access_unavailable appVersion={}", version(request))
                reject(response, HttpStatus.SERVICE_UNAVAILABLE.value(), "device_access_unavailable")
                return
            }
        if (!allowed) {
            accessLogger.info("device_access_denied appVersion={}", version(request))
            reject(response, HttpStatus.FORBIDDEN.value(), "device_access_denied")
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun reject(
        response: HttpServletResponse,
        status: Int,
        code: String,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.writer.write("{\"code\":\"$code\"}")
    }
}

@Configuration(proxyBeanMethods = false)
class InternalDeviceGateConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.internal-device-gate", name = ["enabled"], havingValue = "true")
    internal fun internalDeviceGate(access: InternalDeviceAccess): FilterRegistrationBean<InternalDeviceGateFilter> =
        FilterRegistrationBean(InternalDeviceGateFilter(access)).apply {
            addUrlPatterns("/*")
        }
}
