package com.gijimemo.data.repository

import com.gijimemo.data.db.SessionDao
import com.gijimemo.data.db.toDomain
import com.gijimemo.data.db.toEntity
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val dao: SessionDao
) {
    fun observeAll(): Flow<List<Session>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getById(id: String): Session? = dao.getById(id)?.toDomain()

    suspend fun save(session: Session) = dao.insert(session.toEntity())

    suspend fun updateStatus(id: String, status: SessionStatus, error: String? = null) =
        dao.updateStatus(id, status.name, error)

    suspend fun delete(id: String) = dao.deleteById(id)
}