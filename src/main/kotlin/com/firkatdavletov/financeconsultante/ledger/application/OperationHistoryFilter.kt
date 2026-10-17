package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import java.time.LocalDate
import java.util.UUID

data class OperationHistoryFilter(
    val type: Direction? = null,
    val categoryId: UUID? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
)
