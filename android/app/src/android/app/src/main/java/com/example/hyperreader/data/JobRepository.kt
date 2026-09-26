package com.example.hyperreader.data

import com.example.hyperreader.model.ExportJob
import com.example.hyperreader.model.JobError
import com.example.hyperreader.model.JobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

class JobRepository(private val rootDirectory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jobsDirectory = File(rootDirectory, "jobs").apply { mkdirs() }

    suspend fun loadAll(): List<ExportJob> = withContext(Dispatchers.IO) {
        jobsDirectory.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            runCatching {
                val job = json.decodeFromString<ExportJob>(file.readText())
                if (job.status == JobStatus.queued || job.status == JobStatus.running) {
                    job.copy(
                        status = JobStatus.failed,
                        error = JobError("APP_INTERRUPTED", "上次任务因应用被系统终止而中断，请重新生成。"),
                        finishedAt = Instant.now().toString(),
                        progress = job.progress.copy(phase = com.example.hyperreader.model.JobPhase.failed, message = "任务已中断"),
                    ).also { persistBlocking(it) }
                } else job
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    suspend fun save(job: ExportJob): Unit = withContext(Dispatchers.IO) { persistBlocking(job) }

    fun readBlocking(job: ExportJob): ExportJob = job

    private fun persistBlocking(job: ExportJob) {
        val target = File(jobsDirectory, "${job.id}.json")
        val temporary = File(jobsDirectory, "${job.id}.json.tmp")
        temporary.writeText(json.encodeToString(ExportJob.serializer(), job))
        if (!temporary.renameTo(target)) {
            target.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
