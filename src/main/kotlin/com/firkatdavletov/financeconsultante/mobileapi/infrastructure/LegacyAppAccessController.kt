package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class LegacyAppAccessController {
    @GetMapping("/api/v4/app/access", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun access(): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf("mode" to "OFFLINE_FREE"))
}
