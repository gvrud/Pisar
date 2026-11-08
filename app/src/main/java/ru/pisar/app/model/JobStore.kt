package ru.pisar.app.model

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object JobStore {

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val lock = Any()
    private lateinit var storage: File
    private var lastPersistAt = 0L

    fun init(context: Context) {
        synchronized(lock) {
            storage = File(context.filesDir, "jobs.json")
            _jobs.value = read()
        }
    }

    fun snapshot(): List<Job> = _jobs.value

    fun find(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    fun add(job: Job) = mutate { list -> listOf(job) + list }

    fun update(id: String, transform: (Job) -> Job) = mutate { list ->
        list.map { job -> if (job.id == id) transform(job) else job }
    }

    fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private fun mutate(block: (List<Job>) -> List<Job>) {
        synchronized(lock) {
            val previous = _jobs.value
            val next = block(previous)
            _jobs.value = next

            val now = SystemClock.elapsedRealtime()
            val structural = next.size != previous.size || next.any { job ->
                job.status.isFinished && previous.none { old -> old.id == job.id && old.status == job.status }
            }
            if (structural || now - lastPersistAt >= 2000L) {
                persist(next)
                lastPersistAt = now
            }
        }
    }

    private fun read(): List<Job> {
        val file = File(storage.parentFile, storage.name)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            val result = ArrayList<Job>(array.length())
            for (i in 0 until array.length()) result.add(Job.fromJson(array.getJSONObject(i)))
            result
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<Job>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        runCatching {
            val tmp = File(storage.parentFile, "${storage.name}.tmp")
            tmp.writeText(array.toString())
            if (storage.exists()) storage.delete()
            tmp.renameTo(storage)
        }
    }

    fun exportJson(): String {
        val array = JSONArray()
        _jobs.value.forEach { array.put(it.toJson()) }
        return JSONObject().put("jobs", array).toString(2)
    }
}
